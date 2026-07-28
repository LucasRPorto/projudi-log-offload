package br.jus.tjgo.projudi.logwriter;

import java.util.logging.Level;
import java.util.logging.Logger;

import br.jus.tjgo.projudi.logwriter.logtipo.ClickHouseLogTipoResolver;
import br.jus.tjgo.projudi.logwriter.logtipo.LogTipoResolver;
import br.jus.tjgo.projudi.logwriter.sink.BufferedLogSink;
import br.jus.tjgo.projudi.logwriter.sink.ClickHouseLogSink;

/**
 * Ponto de entrada único da biblioteca — <b>o que a LogPs chama</b>.
 *
 * <h3>Como a LogPs troca de destino</h3>
 *
 * <p>O corpo atual de {@code LogPs.inserir(LogDt)} é extraído sem alteração para
 * um método privado {@code inserirNoOracle(LogDt)} (renomeação pura, reversível
 * com um Ctrl+Z), e {@code inserir} passa a ser:</p>
 *
 * <pre>
 * public void inserir(LogDt dados) throws Exception {
 *     dados.setCodigoTemp(String.valueOf(Math.round(Math.random() * 100000)));
 *
 *     LogWriter writer = LogWriter.instancia();
 *     if (writer.ativo()) {
 *         long idLog = writer.inserir(
 *             LogRegistro.novo()
 *                 .tabela(dados.getTabela())
 *                 .idTabela(dados.getId_Tabela())
 *                 .idLogTipo(dados.getId_LogTipo())
 *                 .logTipoCodigo(dados.getLogTipoCodigo())
 *                 .idUsuario(dados.getId_Usuario())
 *                 .ipComputador(dados.getIpComputador())
 *                 .valorAtual(dados.getValorAtual())
 *                 .valorNovo(dados.getValorNovo())
 *                 .codigoTemp(dados.getCodigoTemp())
 *                 .construir());
 *         dados.setId(String.valueOf(idLog));
 *         if (!writer.destino().gravaNoOracle()) {
 *             return;
 *         }
 *     }
 *     inserirNoOracle(dados);
 * }
 * </pre>
 *
 * <p>Note o que <b>não</b> muda: o sorteio do {@code CODIGO_TEMP}, o truncamento
 * da {@code TABELA} (que passou para o Builder, com a mesma regra), o contrato
 * de {@code dados.setId(...)} que a {@code LogNe} consome, e o caminho Oracle
 * inteiro. Com a flag ausente ou em {@code ORACLE}, o fluxo é bit a bit o de
 * hoje.</p>
 *
 * <h3>Ciclo de vida</h3>
 *
 * <p>{@link #configurar(LogWriterConfig, LogSink)} deve ser chamado uma vez na
 * subida da aplicação (um {@code ServletContextListener} serve), e
 * {@link #encerrar()} na descida — é o {@code encerrar()} que drena a fila e
 * fecha a janela de perda. Sem configuração explícita, a instância se
 * autoconfigura a partir do ambiente na primeira chamada; como o padrão de
 * {@code destino} é {@code ORACLE}, isso significa "inativo".</p>
 */
public final class LogWriter {

    private static final Logger LOG = Logger.getLogger(LogWriter.class.getName());

    private static volatile LogWriter instancia;

    private final LogWriterConfig config;
    private final LogSink sink;
    private final IdGerador idGerador;

    private LogWriter(LogWriterConfig config, LogSink sink, IdGerador idGerador) {
        this.config = config;
        this.sink = sink;
        this.idGerador = idGerador;
    }

    /**
     * Monta a biblioteca. Chamada mais de uma vez, encerra a instância anterior.
     *
     * @param fallback sink usado quando o ClickHouse falha ou a fila satura.
     *        Dentro do Projudi é o caminho legado da própria LogPs; no
     *        laboratório e no benchmark, um
     *        {@link br.jus.tjgo.projudi.logwriter.sink.OracleLogSink}. Pode ser
     *        {@code null} — e aí falha do destino significa perda, o que só é
     *        aceitável em teste.
     */
    public static synchronized void configurar(LogWriterConfig config, LogSink fallback) {
        if (config == null) {
            throw new IllegalArgumentException("config não pode ser nula");
        }
        encerrar();

        if (!config.getDestino().ativo()) {
            instancia = new LogWriter(config, null, null);
            LOG.log(Level.INFO, "log-writer inativo (destino=ORACLE). O Projudi grava como sempre gravou.");
            return;
        }

        ConexaoSupplier conexoes = new ConexaoSupplier.DoDriverManager(
                config.getClickHouseUrl(), config.getClickHouseUsuario(), config.getClickHouseSenha());

        LogTipoResolver resolver = ClickHouseLogTipoResolver.comCache(conexoes);
        LogSink clickHouse = new ClickHouseLogSink(conexoes, ClickHouseLogSink.TABELA_PADRAO, resolver);
        LogSink bufferizado = new BufferedLogSink(clickHouse, fallback, config);

        long workerId = IdGerador.resolverWorkerId(config.getWorkerId());
        instancia = new LogWriter(config, bufferizado, new IdGerador(workerId));

        LOG.log(Level.INFO, "log-writer ativo: {0}", config);
    }

    /** Monta com um sink pronto — usado pelos testes e pelo harness. */
    public static synchronized void configurar(LogWriterConfig config, LogSink sink, long workerId) {
        encerrar();
        instancia = new LogWriter(config, sink, new IdGerador(workerId));
    }

    /**
     * Instância corrente; autoconfigura pelo ambiente na primeira chamada.
     * Como o padrão do ambiente é {@code destino=ORACLE}, o efeito de nunca
     * chamar {@code configurar} é a biblioteca ficar inerte.
     */
    public static LogWriter instancia() {
        LogWriter local = instancia;
        if (local == null) {
            synchronized (LogWriter.class) {
                if (instancia == null) {
                    configurar(LogWriterConfig.doAmbiente(), null);
                }
                local = instancia;
            }
        }
        return local;
    }

    /** Drena, fecha e descarta a instância. Idempotente. */
    public static synchronized void encerrar() {
        LogWriter local = instancia;
        instancia = null;
        if (local != null && local.sink != null) {
            local.sink.close();
        }
    }

    /** {@code true} quando o log-writer assume a gravação. */
    public boolean ativo() {
        return config.getDestino().ativo() && sink != null;
    }

    public LogDestino destino() {
        return config.getDestino();
    }

    public LogWriterConfig config() {
        return config;
    }

    public Metricas metricas() {
        return (sink == null) ? new Metricas() : sink.metricas();
    }

    public LogSink sink() {
        return sink;
    }

    /**
     * Grava o registro e devolve o {@code ID_LOG} atribuído.
     *
     * <p>Não lança: o sink que a LogPs enxerga trata a falha desviando para o
     * fallback. O ID volta preenchido mesmo que a gravação ainda esteja na fila
     * — é o que permite que {@code dados.setId(...)} continue funcionando com
     * escrita assíncrona.</p>
     */
    public long inserir(LogRegistro registro) {
        if (registro == null) {
            throw new IllegalArgumentException("registro não pode ser nulo");
        }
        if (!ativo()) {
            throw new IllegalStateException(
                    "log-writer inativo (destino=" + config.getDestino() + "); "
                            + "a LogPs deve checar ativo() antes de chamar inserir()");
        }

        long idLog = (registro.getIdLog() != 0L) ? registro.getIdLog() : idGerador.proximo();
        LogRegistro comId = (registro.getIdLog() == idLog) ? registro : registro.comId(idLog);

        try {
            sink.escrever(comId);
        } catch (LogWriterException e) {
            // Só acontece com um sink que não seja o BufferedLogSink (teste,
            // harness). Gravar log não derruba operação de negócio nunca.
            LOG.log(Level.SEVERE, "Falha ao gravar log de auditoria ID_LOG=" + idLog, e);
        }
        return idLog;
    }

    /** Próximo ID sem gravar nada — usado pelo harness de benchmark. */
    public long proximoId() {
        if (idGerador == null) {
            throw new IllegalStateException("log-writer inativo: não há gerador de ID");
        }
        return idGerador.proximo();
    }
}
