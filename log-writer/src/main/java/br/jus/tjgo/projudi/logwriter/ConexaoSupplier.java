package br.jus.tjgo.projudi.logwriter;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fonte de conexões JDBC.
 *
 * <p>É a costura que torna os sinks testáveis sem banco: o teste injeta um
 * supplier que devolve uma conexão de mentira e verifica o SQL e os parâmetros
 * ligados, sem ClickHouse nem Oracle de pé.</p>
 *
 * <p>Também é o ponto de extensão para o Projudi usar o pool que já existe lá
 * ({@code FabricaConexao}) em vez do {@code DriverManager}, se um dia a
 * gravação de log passar a compartilhar pool com o resto da aplicação.</p>
 */
public interface ConexaoSupplier {

    Connection obter() throws SQLException, LogWriterException;

    /**
     * Implementação padrão sobre {@link DriverManager}, com <b>registro
     * explícito do driver</b>.
     *
     * <h3>Por que o registro explícito é necessário</h3>
     *
     * <p>Sem isto, a conexão falha com
     * {@code No suitable driver found for jdbc:ch://…} mesmo com o
     * {@code clickhouse-jdbc} presente no classpath — confirmado ao rodar o
     * teste de integração.</p>
     *
     * <p><b>A causa não é o uber jar.</b> O
     * {@code META-INF/services/java.sql.Driver} sobrevive ao shade: o
     * {@code clickhouse-jdbc-0.7.2-all.jar} o contém, listando
     * {@code com.clickhouse.jdbc.ClickHouseDriver} e
     * {@code com.clickhouse.jdbc.Driver}, e as classes estão nos pacotes
     * originais. O arquivo de serviço está lá; o que não acontece é a leitura
     * dele.</p>
     *
     * <p>O {@link DriverManager} varre os provedores SPI <b>uma única vez</b>,
     * no seu próprio inicializador estático, usando o <i>context classloader</i>
     * da thread naquele instante. Quando ele é inicializado antes do classpath
     * da aplicação estar visível para aquele classloader — o que acontece sob o
     * Surefire e sob o {@code exec:java}, que montam classloaders próprios — a
     * varredura não encontra nada, e <b>não é refeita</b>. Nenhum driver
     * carregado depois entra por SPI.</p>
     *
     * <p>{@code Class.forName} contorna isso porque o inicializador estático do
     * próprio driver chama {@code DriverManager.registerDriver}, e a verificação
     * de classloader que o {@code getConnection} faz passa: quem carregou a
     * classe é o mesmo classloader de quem está chamando.</p>
     *
     * <p>A carga é <b>por tentativa e melhor esforço</b>, e não uma exigência de
     * inicialização: este supplier atende ClickHouse e Oracle, e o driver da
     * Oracle está em escopo {@code test} — exigi-lo aqui quebraria o uso normal
     * da biblioteca dentro do Projudi. Uma classe que falta é registrada e só
     * vira erro quando alguém tenta usar a URL correspondente.</p>
     */
    final class DoDriverManager implements ConexaoSupplier {

        private static final Logger LOG = Logger.getLogger(DoDriverManager.class.getName());

        /** Um driver que esta biblioteca sabe carregar sozinha. */
        private static final class DriverConhecido {
            final String classe;
            final String rotulo;
            final String artefato;

            DriverConhecido(String classe, String rotulo, String artefato) {
                this.classe = classe;
                this.rotulo = rotulo;
                this.artefato = artefato;
            }
        }

        private static final DriverConhecido CLICKHOUSE = new DriverConhecido(
                "com.clickhouse.jdbc.ClickHouseDriver",
                "ClickHouse",
                "com.clickhouse:clickhouse-jdbc:0.7.2 (classificador 'all')");

        private static final DriverConhecido ORACLE = new DriverConhecido(
                "oracle.jdbc.OracleDriver",
                "Oracle",
                "com.oracle.database.jdbc:ojdbc8 (escopo test neste módulo)");

        /** Prefixo de URL JDBC → driver que a atende. */
        private static final Map<String, DriverConhecido> POR_PREFIXO;

        /** Classe do driver → motivo pelo qual não pôde ser carregada. */
        private static final Map<String, Throwable> FALHAS_DE_CARGA;

        static {
            Map<String, DriverConhecido> prefixos = new LinkedHashMap<String, DriverConhecido>();
            prefixos.put("jdbc:ch:", CLICKHOUSE);
            prefixos.put("jdbc:clickhouse:", CLICKHOUSE);
            prefixos.put("jdbc:oracle:", ORACLE);
            POR_PREFIXO = Collections.unmodifiableMap(prefixos);

            Map<String, Throwable> falhas = new LinkedHashMap<String, Throwable>();
            for (DriverConhecido driver : new DriverConhecido[]{CLICKHOUSE, ORACLE}) {
                if (falhas.containsKey(driver.classe)) {
                    continue;
                }
                try {
                    // Inicializa a classe: é o static block do driver que chama
                    // DriverManager.registerDriver.
                    Class.forName(driver.classe);
                    LOG.log(Level.FINE, "Driver JDBC registrado explicitamente: {0}", driver.classe);
                } catch (ClassNotFoundException e) {
                    falhas.put(driver.classe, e);
                } catch (LinkageError e) {
                    // Cobre NoClassDefFoundError e ExceptionInInitializerError:
                    // a classe existe mas não pôde ser inicializada (conflito de
                    // versão no classpath, dependência ausente). Vira a mesma
                    // mensagem clara em vez de derrubar a carga da classe.
                    falhas.put(driver.classe, e);
                } catch (RuntimeException e) {
                    falhas.put(driver.classe, e);
                }
            }
            FALHAS_DE_CARGA = Collections.unmodifiableMap(falhas);
        }

        private final String url;
        private final String usuario;
        private final String senha;

        public DoDriverManager(String url, String usuario, String senha) {
            this.url = url;
            this.usuario = usuario;
            this.senha = (senha == null) ? "" : senha;
        }

        @Override
        public Connection obter() throws SQLException, LogWriterException {
            exigirDriverCarregado(url);

            Properties props = new Properties();
            if (usuario != null) {
                props.setProperty("user", usuario);
            }
            props.setProperty("password", senha);
            return DriverManager.getConnection(url, props);
        }

        /**
         * Falha com mensagem acionável quando o driver que atende esta URL não
         * pôde ser carregado — em vez de deixar o {@link DriverManager} devolver
         * um {@code No suitable driver found}, que aponta para a URL quando o
         * problema é o classpath.
         *
         * <p>URL de esquema desconhecido passa direto: quem quiser usar outro
         * banco registra o driver por conta própria, e o {@code DriverManager}
         * decide.</p>
         */
        static void exigirDriverCarregado(String url) throws LogWriterException {
            DriverConhecido driver = driverPara(url);
            if (driver == null) {
                return;
            }
            Throwable falha = FALHAS_DE_CARGA.get(driver.classe);
            if (falha != null) {
                // A causa raiz vai no texto, não só encadeada: foi exatamente
                // ela que o DriverManager engoliu, e é ela que aponta o
                // problema (no caso conhecido, org/slf4j/LoggerFactory ausente).
                throw new LogWriterException(
                        mensagemDeDriverAusente(url) + " Causa raiz: " + falha, falha);
            }
        }

        private static DriverConhecido driverPara(String url) {
            if (url == null) {
                return null;
            }
            String normalizada = url.trim().toLowerCase();
            for (Map.Entry<String, DriverConhecido> entrada : POR_PREFIXO.entrySet()) {
                if (normalizada.startsWith(entrada.getKey())) {
                    return entrada.getValue();
                }
            }
            return null;
        }

        /**
         * Mensagem de diagnóstico para a URL. {@code null} quando o esquema não
         * é de um driver conhecido. Package-private para o teste.
         */
        static String mensagemDeDriverAusente(String url) {
            DriverConhecido driver = driverPara(url);
            if (driver == null) {
                return null;
            }
            return "Driver JDBC do " + driver.rotulo + " ausente no classpath: não foi possível"
                    + " carregar a classe " + driver.classe + ", exigida pela URL " + url + "."
                    + " Verifique se o artefato " + driver.artefato + " está no classpath"
                    + " do processo. (Este módulo registra o driver explicitamente porque o"
                    + " DriverManager varre os provedores SPI uma única vez, na própria"
                    + " inicialização, e não os reavalia depois.)";
        }

        /** {@code true} se o driver que atende esta URL está carregado. */
        public static boolean driverDisponivelPara(String url) {
            DriverConhecido driver = driverPara(url);
            return driver == null || !FALHAS_DE_CARGA.containsKey(driver.classe);
        }

        @Override
        public String toString() {
            return "DriverManager[" + url + " como " + usuario + "]";
        }
    }
}
