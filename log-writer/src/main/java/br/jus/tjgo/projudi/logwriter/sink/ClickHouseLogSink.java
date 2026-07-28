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
import br.jus.tjgo.projudi.logwriter.logtipo.LogTipoResolver;

/**
 * Gravação em {@code projudi_logs.log_raw} via JDBC.
 *
 * <p>Sink folha: escreve e falha, sem fila e sem fallback. Quem quiser
 * resiliência compõe com {@link BufferedLogSink}.</p>
 *
 * <h3>INSERT fixo, não dinâmico</h3>
 *
 * <p>A LogPs monta o INSERT coluna a coluna, incluindo apenas o que não está
 * vazio. Aqui o comando é <b>fixo, com as 13 colunas sempre presentes</b>, e o
 * vazio vira {@code ''} ou {@code NULL} conforme a nulabilidade declarada no
 * DDL. Duas razões: um SQL constante permite reaproveitar o
 * {@code PreparedStatement} no lote inteiro (o ganho de desempenho que a
 * Solução 1 quer medir depende disso), e a {@code log_raw} já foi modelada com
 * {@code ''} como "ausente" justamente nas colunas que a LogPs omitia.</p>
 *
 * <h3>Conexão</h3>
 *
 * <p>Uma conexão é aberta na primeira gravação e reaproveitada. Ao primeiro
 * {@link SQLException} ela é descartada e reaberta na chamada seguinte — o
 * driver do ClickHouse fala HTTP, então reabrir é barato e evita carregar uma
 * conexão morta por tempo indeterminado.</p>
 */
public final class ClickHouseLogSink implements LogSink {

    private static final Logger LOG = Logger.getLogger(ClickHouseLogSink.class.getName());

    public static final String TABELA_PADRAO = "projudi_logs.log_raw";

    private final ConexaoSupplier conexoes;
    private final String tabela;
    private final String sql;
    private final LogTipoResolver resolverLogTipo;
    private final Metricas metricas = new Metricas();

    private Connection conexao;

    public ClickHouseLogSink(ConexaoSupplier conexoes) {
        this(conexoes, TABELA_PADRAO, LogTipoResolver.INERTE);
    }

    public ClickHouseLogSink(ConexaoSupplier conexoes, String tabela, LogTipoResolver resolverLogTipo) {
        if (conexoes == null) {
            throw new IllegalArgumentException("conexoes não pode ser nulo");
        }
        this.conexoes = conexoes;
        this.tabela = (tabela == null || tabela.trim().isEmpty()) ? TABELA_PADRAO : tabela.trim();
        this.resolverLogTipo = (resolverLogTipo == null) ? LogTipoResolver.INERTE : resolverLogTipo;
        this.sql = montarSql(this.tabela);
    }

    static String montarSql(String tabela) {
        return "INSERT INTO " + tabela + " ("
                + "ID_LOG, ID_LOG_TIPO, ID_USU, IP_COMPUTADOR, DATA, HORA, TABELA, "
                + "VALOR_ATUAL, VALOR_NOVO, CODIGO_TEMP, ID_TABELA, HASH, QTD_ERROS_DIA"
                + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)";
    }

    /** O SQL efetivamente usado — verificado pelo teste unitário. */
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
                vincular(ps, registro, resolverLogTipo);
                ps.addBatch();
            }
            ps.executeBatch();

            metricas.somarGravadosDestino(registros.size());
            metricas.somarLotesGravados(1L);
        } catch (SQLException e) {
            descartarConexao();
            metricas.somarLotesComFalha(1L);
            throw new LogWriterException(
                    "Falha ao gravar lote de " + registros.size() + " registro(s) em " + tabela, e);
        } finally {
            metricas.somarNanosEmFlush(System.nanoTime() - inicio);
            fecharSilencioso(ps);
        }
    }

    /**
     * Liga as 13 colunas na ordem do INSERT.
     *
     * <p>{@code static} e de visibilidade de pacote para que o teste unitário
     * exercite a ligação de parâmetros isoladamente, com um
     * {@code PreparedStatement} de mentira.</p>
     */
    static void vincular(PreparedStatement ps, LogRegistro r, LogTipoResolver resolver) throws SQLException {
        long idLogTipo = r.getIdLogTipo();
        if (idLogTipo == 0L && r.getLogTipoCodigo() != 0L) {
            idLogTipo = resolver.resolver(r.getLogTipoCodigo());
        }

        ps.setLong(1, r.getIdLog());
        ps.setLong(2, idLogTipo);
        ps.setLong(3, r.getIdUsu());
        ps.setString(4, r.getIpComputador());
        ps.setTimestamp(5, new Timestamp(r.getData().getTime()));
        ps.setTimestamp(6, new Timestamp(r.getHora().getTime()));
        ps.setString(7, r.getTabela());
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

    private Connection conexaoAtiva() throws SQLException, LogWriterException {
        if (conexao == null || conexao.isClosed()) {
            conexao = conexoes.obter();
        }
        return conexao;
    }

    private void descartarConexao() {
        if (conexao != null) {
            try {
                conexao.close();
            } catch (SQLException e) {
                LOG.log(Level.FINE, "Erro ao fechar conexão já defeituosa do ClickHouse", e);
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
        return "ClickHouseLogSink[" + tabela + " via " + conexoes + "]";
    }
}
