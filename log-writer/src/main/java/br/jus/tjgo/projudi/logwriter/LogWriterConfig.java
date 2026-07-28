package br.jus.tjgo.projudi.logwriter;

/**
 * Configuração da biblioteca, resolvida de <b>system property</b> e, na falta
 * dela, de <b>variável de ambiente</b>.
 *
 * <p>Sem framework de configuração de propósito: o Projudi é uma aplicação
 * legada em Tomcat, e a forma que sempre funciona ali é {@code -D} no
 * {@code JAVA_OPTS}. As variáveis de ambiente existem para o Docker e para o
 * harness de benchmark.</p>
 *
 * <table border="1">
 *   <caption>Chaves reconhecidas</caption>
 *   <tr><th>System property</th><th>Variável de ambiente</th><th>Padrão</th></tr>
 *   <tr><td>projudi.logwriter.destino</td><td>PROJUDI_LOGWRITER_DESTINO</td><td>ORACLE</td></tr>
 *   <tr><td>projudi.logwriter.clickhouse.url</td><td>PROJUDI_LOGWRITER_CLICKHOUSE_URL</td><td>jdbc:ch://localhost:8123/projudi_logs</td></tr>
 *   <tr><td>projudi.logwriter.clickhouse.usuario</td><td>PROJUDI_LOGWRITER_CLICKHOUSE_USUARIO</td><td>projudi_app</td></tr>
 *   <tr><td>projudi.logwriter.clickhouse.senha</td><td>PROJUDI_LOGWRITER_CLICKHOUSE_SENHA</td><td>(vazio)</td></tr>
 *   <tr><td>projudi.logwriter.lote.max</td><td>PROJUDI_LOGWRITER_LOTE_MAX</td><td>500</td></tr>
 *   <tr><td>projudi.logwriter.lote.intervaloMs</td><td>PROJUDI_LOGWRITER_LOTE_INTERVALO_MS</td><td>1000</td></tr>
 *   <tr><td>projudi.logwriter.fila.capacidade</td><td>PROJUDI_LOGWRITER_FILA_CAPACIDADE</td><td>10000</td></tr>
 *   <tr><td>projudi.logwriter.tentativas</td><td>PROJUDI_LOGWRITER_TENTATIVAS</td><td>2</td></tr>
 *   <tr><td>projudi.logwriter.workerId</td><td>PROJUDI_LOGWRITER_WORKER_ID</td><td>derivado de hostname+PID</td></tr>
 * </table>
 *
 * <p><b>O padrão de {@code destino} é ORACLE.</b> Subir a biblioteca no
 * classpath sem configurar nada não muda comportamento nenhum.</p>
 */
public final class LogWriterConfig {

    public static final String P_DESTINO = "projudi.logwriter.destino";
    public static final String P_CH_URL = "projudi.logwriter.clickhouse.url";
    public static final String P_CH_USUARIO = "projudi.logwriter.clickhouse.usuario";
    public static final String P_CH_SENHA = "projudi.logwriter.clickhouse.senha";
    public static final String P_LOTE_MAX = "projudi.logwriter.lote.max";
    public static final String P_LOTE_INTERVALO = "projudi.logwriter.lote.intervaloMs";
    public static final String P_FILA_CAPACIDADE = "projudi.logwriter.fila.capacidade";
    public static final String P_TENTATIVAS = "projudi.logwriter.tentativas";
    public static final String P_WORKER_ID = "projudi.logwriter.workerId";

    public static final String URL_PADRAO = "jdbc:ch://localhost:8123/projudi_logs";
    public static final String USUARIO_PADRAO = "projudi_app";

    private LogDestino destino = LogDestino.ORACLE;
    private String clickHouseUrl = URL_PADRAO;
    private String clickHouseUsuario = USUARIO_PADRAO;
    private String clickHouseSenha = "";
    private int loteMax = 500;
    private long intervaloFlushMs = 1000L;
    private int filaCapacidade = 10000;
    private int tentativas = 2;
    private Long workerId;

    public static LogWriterConfig padrao() {
        return new LogWriterConfig();
    }

    /** Lê system properties e, na ausência delas, variáveis de ambiente. */
    public static LogWriterConfig doAmbiente() {
        LogWriterConfig c = new LogWriterConfig();
        c.destino = LogDestino.de(ler(P_DESTINO));
        c.clickHouseUrl = lerOu(P_CH_URL, URL_PADRAO);
        c.clickHouseUsuario = lerOu(P_CH_USUARIO, USUARIO_PADRAO);
        c.clickHouseSenha = lerOu(P_CH_SENHA, "");
        c.loteMax = lerInt(P_LOTE_MAX, c.loteMax);
        c.intervaloFlushMs = lerLong(P_LOTE_INTERVALO, c.intervaloFlushMs);
        c.filaCapacidade = lerInt(P_FILA_CAPACIDADE, c.filaCapacidade);
        c.tentativas = lerInt(P_TENTATIVAS, c.tentativas);
        String worker = ler(P_WORKER_ID);
        c.workerId = (worker == null || worker.trim().isEmpty()) ? null : Long.valueOf(worker.trim());
        return c;
    }

    /**
     * Nome da variável de ambiente equivalente a uma system property:
     * {@code projudi.logwriter.lote.max} → {@code PROJUDI_LOGWRITER_LOTE_MAX}.
     * O camelCase vira separador para que {@code intervaloMs} case com
     * {@code INTERVALO_MS}.
     */
    static String nomeDeAmbiente(String propriedade) {
        StringBuilder sb = new StringBuilder(propriedade.length() + 4);
        for (int i = 0; i < propriedade.length(); i++) {
            char c = propriedade.charAt(i);
            if (c == '.') {
                sb.append('_');
            } else if (Character.isUpperCase(c) && i > 0 && Character.isLowerCase(propriedade.charAt(i - 1))) {
                sb.append('_').append(c);
            } else {
                sb.append(Character.toUpperCase(c));
            }
        }
        return sb.toString();
    }

    static String ler(String propriedade) {
        String valor = System.getProperty(propriedade);
        if (valor != null && !valor.trim().isEmpty()) {
            return valor.trim();
        }
        valor = System.getenv(nomeDeAmbiente(propriedade));
        if (valor != null && !valor.trim().isEmpty()) {
            return valor.trim();
        }
        return null;
    }

    private static String lerOu(String propriedade, String padrao) {
        String valor = ler(propriedade);
        return (valor == null) ? padrao : valor;
    }

    private static int lerInt(String propriedade, int padrao) {
        String valor = ler(propriedade);
        try {
            return (valor == null) ? padrao : Integer.parseInt(valor);
        } catch (NumberFormatException e) {
            return padrao;
        }
    }

    private static long lerLong(String propriedade, long padrao) {
        String valor = ler(propriedade);
        try {
            return (valor == null) ? padrao : Long.parseLong(valor);
        } catch (NumberFormatException e) {
            return padrao;
        }
    }

    public LogDestino getDestino() {
        return destino;
    }

    public LogWriterConfig destino(LogDestino valor) {
        this.destino = (valor == null) ? LogDestino.ORACLE : valor;
        return this;
    }

    public String getClickHouseUrl() {
        return clickHouseUrl;
    }

    public LogWriterConfig clickHouseUrl(String valor) {
        this.clickHouseUrl = valor;
        return this;
    }

    public String getClickHouseUsuario() {
        return clickHouseUsuario;
    }

    public LogWriterConfig clickHouseUsuario(String valor) {
        this.clickHouseUsuario = valor;
        return this;
    }

    public String getClickHouseSenha() {
        return clickHouseSenha;
    }

    public LogWriterConfig clickHouseSenha(String valor) {
        this.clickHouseSenha = (valor == null) ? "" : valor;
        return this;
    }

    /**
     * Tamanho máximo do lote enviado ao ClickHouse. {@code 1} equivale a
     * gravação síncrona, um INSERT por chamada.
     */
    public int getLoteMax() {
        return loteMax;
    }

    public LogWriterConfig loteMax(int valor) {
        if (valor < 1) {
            throw new IllegalArgumentException("lote.max precisa ser >= 1");
        }
        this.loteMax = valor;
        return this;
    }

    /** Idade máxima de um registro na fila antes do flush por tempo. */
    public long getIntervaloFlushMs() {
        return intervaloFlushMs;
    }

    public LogWriterConfig intervaloFlushMs(long valor) {
        if (valor < 1) {
            throw new IllegalArgumentException("lote.intervaloMs precisa ser >= 1");
        }
        this.intervaloFlushMs = valor;
        return this;
    }

    /**
     * Teto da fila em memória. Junto com {@link #getLoteMax()}, delimita a
     * janela de perda em caso de morte abrupta da JVM — ver docs/decisoes.md,
     * decisão 19.
     */
    public int getFilaCapacidade() {
        return filaCapacidade;
    }

    public LogWriterConfig filaCapacidade(int valor) {
        if (valor < 1) {
            throw new IllegalArgumentException("fila.capacidade precisa ser >= 1");
        }
        this.filaCapacidade = valor;
        return this;
    }

    /** Tentativas de gravação de um lote no destino antes de ir ao fallback. */
    public int getTentativas() {
        return tentativas;
    }

    public LogWriterConfig tentativas(int valor) {
        if (valor < 1) {
            throw new IllegalArgumentException("tentativas precisa ser >= 1");
        }
        this.tentativas = valor;
        return this;
    }

    public Long getWorkerId() {
        return workerId;
    }

    public LogWriterConfig workerId(Long valor) {
        this.workerId = valor;
        return this;
    }

    @Override
    public String toString() {
        return "LogWriterConfig[destino=" + destino
                + ", url=" + clickHouseUrl
                + ", usuario=" + clickHouseUsuario
                + ", loteMax=" + loteMax
                + ", intervaloFlushMs=" + intervaloFlushMs
                + ", filaCapacidade=" + filaCapacidade
                + ", tentativas=" + tentativas
                + ", workerId=" + workerId + "]";
    }
}
