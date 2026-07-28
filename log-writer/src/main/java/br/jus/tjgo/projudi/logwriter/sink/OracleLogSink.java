package br.jus.tjgo.projudi.logwriter.sink;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import br.jus.tjgo.projudi.logwriter.ConexaoSupplier;
import br.jus.tjgo.projudi.logwriter.LogRegistro;
import br.jus.tjgo.projudi.logwriter.LogSink;
import br.jus.tjgo.projudi.logwriter.LogWriterException;
import br.jus.tjgo.projudi.logwriter.Metricas;

/**
 * Gravação na {@code PROJUDI.LOG} do Oracle, por JDBC puro.
 *
 * <p>Tem dois papéis, e os dois importam:</p>
 *
 * <ol>
 *   <li><b>Grupo de controle do benchmark.</b> Medir o ClickHouse contra "o que
 *       o Projudi faz hoje" exige escrever no Oracle pelo mesmo harness, no
 *       mesmo host, na mesma janela de tempo — ver docs/ambientes.md, seção 3.</li>
 *   <li><b>Fallback pronto para o laboratório.</b> Dentro do Projudi, o fallback
 *       natural é o próprio código legado da LogPs, que já é auditado e conhece
 *       {@code FabricaConexao}; aqui, onde não existe Projudi, este sink faz
 *       esse papel.</li>
 * </ol>
 *
 * <h3>Sobre gravar o ID_LOG explicitamente</h3>
 *
 * <p>Este sink <b>sempre</b> envia {@code ID_LOG}, com o valor gerado pelo
 * {@code IdGerador}. Isso é seguro e foi verificado no DDL de produção
 * ({@code BancoDeDados/07_CreateTrigger.sql}): a trigger
 * {@code LOG_ID_LOG_TRG} só atribui valor
 * {@code IF INSERTING AND :new.Id_Log IS NULL}. Com o ID preenchido, ela não
 * sobrescreve nem consome {@code LOG_ID_LOG_SEQ.NEXTVAL} — a numeração legada
 * segue intacta para os inserts que ainda passam pelo caminho antigo. Ver
 * docs/decisoes.md, decisão 20.</p>
 *
 * <p>A tabela do laboratório tem 13 colunas para espelhar a {@code log_raw}; a
 * de produção tem 11 (não tem {@code HASH} nem {@code QTD_ERROS_DIA}, que vivem
 * na {@code LOG_ERRO}). O construtor permite escolher.</p>
 */
public final class OracleLogSink implements LogSink {

    private static final Logger LOG = Logger.getLogger(OracleLogSink.class.getName());

    public static final String TABELA_PADRAO = "PROJUDI.LOG";

    private final ConexaoSupplier conexoes;
    private final String tabela;
    private final boolean comColunasDeErro;
    private final String sql;
    private final Metricas metricas = new Metricas();

    private Connection conexao;

    public OracleLogSink(ConexaoSupplier conexoes) {
        this(conexoes, TABELA_PADRAO, true);
    }

    /**
     * @param comColunasDeErro {@code true} inclui {@code HASH} e
     *        {@code QTD_ERROS_DIA} (tabela do laboratório, 13 colunas);
     *        {@code false} usa as 11 colunas da PROJUDI.LOG de produção.
     */
    public OracleLogSink(ConexaoSupplier conexoes, String tabela, boolean comColunasDeErro) {
        if (conexoes == null) {
            throw new IllegalArgumentException("conexoes não pode ser nulo");
        }
        this.conexoes = conexoes;
        this.tabela = (tabela == null || tabela.trim().isEmpty()) ? TABELA_PADRAO : tabela.trim();
        this.comColunasDeErro = comColunasDeErro;
        this.sql = montarSql(this.tabela, comColunasDeErro);
    }

    static String montarSql(String tabela, boolean comColunasDeErro) {
        StringBuilder colunas = new StringBuilder(
                "ID_LOG, ID_LOG_TIPO, ID_USU, IP_COMPUTADOR, DATA, HORA, TABELA, "
                        + "VALOR_ATUAL, VALOR_NOVO, CODIGO_TEMP, ID_TABELA");
        StringBuilder valores = new StringBuilder("?,?,?,?,?,?,?,?,?,?,?");
        if (comColunasDeErro) {
            colunas.append(", HASH, QTD_ERROS_DIA");
            valores.append(",?,?");
        }
        return "INSERT INTO " + tabela + " (" + colunas + ") VALUES (" + valores + ")";
    }

    public String getSql() {
        return sql;
    }

    @Override
    public void escrever(LogRegistro registro) throws LogWriterException {
        escreverLote(Collections.singletonList(registro));
    }

    @Override
    public synchronized void escreverLote(List<LogRegistro> registros) throws LogWriterException {
        if (registros == null || registros.isEmpty()) {
            return;
        }
        metricas.somarRecebidos(registros.size());

        long inicio = System.nanoTime();
        PreparedStatement ps = null;
        try {
            Connection cx = conexaoAtiva();
            ps = cx.prepareStatement(sql);
            for (LogRegistro registro : registros) {
                vincular(ps, registro, comColunasDeErro);
                ps.addBatch();
            }
            ps.executeBatch();
            cx.commit();

            metricas.somarGravadosDestino(registros.size());
            metricas.somarLotesGravados(1L);
        } catch (SQLException e) {
            reverterSilencioso();
            descartarConexao();
            metricas.somarLotesComFalha(1L);
            throw new LogWriterException(
                    "Falha ao gravar lote de " + registros.size() + " registro(s) em " + tabela, e);
        } finally {
            metricas.somarNanosEmFlush(System.nanoTime() - inicio);
            fecharSilencioso(ps);
        }
    }

    static void vincular(PreparedStatement ps, LogRegistro r, boolean comColunasDeErro) throws SQLException {
        ps.setLong(1, r.getIdLog());
        ps.setLong(2, r.getIdLogTipo());
        ps.setLong(3, r.getIdUsu());
        ps.setString(4, r.getIpComputador());
        // DATA é DATE no Oracle (data + hora, precisão de segundo); a LogPs usa
        // setDate aqui e setTimestamp na HORA. Mantido igual.
        ps.setDate(5, new java.sql.Date(r.getData().getTime()));
        ps.setTimestamp(6, new Timestamp(r.getHora().getTime()));
        ps.setString(7, r.getTabela());
        // VALOR_ATUAL/VALOR_NOVO são CLOB; a LogPs liga com setString
        // (Persistencia.ajusteValoresPreparedStatement, case Types.CLOB).
        ps.setString(8, r.getValorAtual());
        ps.setString(9, r.getValorNovo());

        if (r.getCodigoTemp() == null) {
            ps.setNull(10, Types.BIGINT);
        } else {
            ps.setLong(10, r.getCodigoTemp().longValue());
        }
        if (r.getIdTabela() == null) {
            ps.setNull(11, Types.BIGINT);
        } else {
            ps.setLong(11, r.getIdTabela().longValue());
        }

        if (comColunasDeErro) {
            if (r.getHash() == null) {
                ps.setNull(12, Types.CHAR);
            } else {
                ps.setString(12, r.getHash());
            }
            if (r.getQtdErrosDia() == null) {
                ps.setNull(13, Types.INTEGER);
            } else {
                ps.setInt(13, r.getQtdErrosDia().intValue());
            }
        }
    }

    private Connection conexaoAtiva() throws SQLException {
        if (conexao == null || conexao.isClosed()) {
            conexao = conexoes.obter();
            conexao.setAutoCommit(false);
        }
        return conexao;
    }

    private void reverterSilencioso() {
        if (conexao != null) {
            try {
                conexao.rollback();
            } catch (SQLException e) {
                LOG.log(Level.FINE, "Erro no rollback do lote Oracle", e);
            }
        }
    }

    private void descartarConexao() {
        if (conexao != null) {
            try {
                conexao.close();
            } catch (SQLException e) {
                LOG.log(Level.FINE, "Erro ao fechar conexão já defeituosa do Oracle", e);
            }
            conexao = null;
        }
    }

    private static void fecharSilencioso(PreparedStatement ps) {
        if (ps != null) {
            try {
                ps.close();
            } catch (SQLException e) {
                LOG.log(Level.FINE, "Erro ao fechar PreparedStatement", e);
            }
        }
    }

    @Override
    public Metricas metricas() {
        return metricas;
    }

    @Override
    public synchronized void close() {
        descartarConexao();
    }

    @Override
    public String toString() {
        return "OracleLogSink[" + tabela + " via " + conexoes + "]";
    }
}
