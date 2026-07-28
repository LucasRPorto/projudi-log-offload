package br.jus.tjgo.projudi.logwriter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Enumeration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Regressão do {@code No suitable driver found for jdbc:ch://…}.
 *
 * <p>O teste de integração falhava com essa mensagem mesmo com o
 * {@code clickhouse-jdbc} no classpath. Estes testes fixam a garantia sem
 * precisar de ClickHouse no ar: verificam que, depois de tocar o
 * {@code DoDriverManager}, o {@link DriverManager} <b>tem</b> um driver que
 * aceita a URL — que é exatamente a condição que faltava.</p>
 */
class ConexaoSupplierTest {

    private static final String URL_CH = "jdbc:ch://localhost:8123/projudi_logs";
    private static final String URL_ORACLE = "jdbc:oracle:thin:@//localhost:1521/FREEPDB1";

    /** Força a inicialização estática do DoDriverManager. */
    private static void tocarClasse() {
        new ConexaoSupplier.DoDriverManager(URL_CH, "u", "s");
    }

    private static boolean algumDriverAceita(String url) throws SQLException {
        Enumeration<Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            if (drivers.nextElement().acceptsURL(url)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("o driver do ClickHouse fica registrado no DriverManager")
    void driverClickHouseRegistrado() throws Exception {
        tocarClasse();
        assertTrue(algumDriverAceita(URL_CH),
                "nenhum driver registrado aceita " + URL_CH + " — é exatamente o estado que "
                        + "produz 'No suitable driver found'. O registro explícito em "
                        + "DoDriverManager não aconteceu.");
    }

    @Test
    @DisplayName("o esquema alternativo jdbc:clickhouse: também é aceito")
    void esquemaAlternativoAceito() throws Exception {
        tocarClasse();
        assertTrue(algumDriverAceita("jdbc:clickhouse://localhost:8123/projudi_logs"));
    }

    @Test
    @Timeout(30)
    @DisplayName("com o driver presente, obter() não falha por driver ausente")
    void obterNaoFalhaPorDriverAusente() {
        // Aponta para uma porta fechada de propósito: o que se verifica aqui é
        // que a falha vem da CONEXÃO, não da ausência de driver. Sem o registro
        // explícito, viria um SQLException 'No suitable driver found' antes de
        // qualquer tentativa de rede.
        //
        // Timeouts curtos na própria URL: sem eles o driver insiste com os
        // padrões dele e este teste sozinho custa segundos ao `mvn test`.
        ConexaoSupplier supplier = new ConexaoSupplier.DoDriverManager(
                "jdbc:ch://127.0.0.1:1/x?connect_timeout=300&socket_timeout=300", "u", "s");
        try {
            supplier.obter().close();
            // Se algo estiver escutando na porta 1, o teste não tem o que provar.
        } catch (LogWriterException e) {
            fail("obter() reportou driver ausente com o clickhouse-jdbc no classpath: "
                    + e.getMessage());
        } catch (Exception e) {
            String mensagem = String.valueOf(e.getMessage());
            assertFalse(mensagem.contains("No suitable driver"),
                    "a falha deveria ser de conexão, não de driver: " + mensagem);
        }
    }

    @Test
    @DisplayName("driverDisponivelPara reconhece os esquemas conhecidos")
    void disponibilidadePorEsquema() {
        assertTrue(ConexaoSupplier.DoDriverManager.driverDisponivelPara(URL_CH));
        assertTrue(ConexaoSupplier.DoDriverManager.driverDisponivelPara(URL_ORACLE),
                "o ojdbc8 está em escopo test, então deve estar disponível durante os testes");
        // Esquema desconhecido não é problema desta classe: passa direto e o
        // DriverManager decide.
        assertTrue(ConexaoSupplier.DoDriverManager.driverDisponivelPara("jdbc:postgresql://x/y"));
        assertTrue(ConexaoSupplier.DoDriverManager.driverDisponivelPara(null));
    }

    @Test
    @DisplayName("URL de esquema desconhecido não é barrada pela verificação")
    void esquemaDesconhecidoPassaDireto() throws Exception {
        ConexaoSupplier.DoDriverManager.exigirDriverCarregado("jdbc:postgresql://localhost/x");
        ConexaoSupplier.DoDriverManager.exigirDriverCarregado(null);
        assertNull(ConexaoSupplier.DoDriverManager.mensagemDeDriverAusente("jdbc:postgresql://x/y"));
    }

    @Test
    @DisplayName("a mensagem de driver ausente nomeia a classe, a URL e o artefato Maven")
    void mensagemDeDiagnostico() {
        String mensagemCh = ConexaoSupplier.DoDriverManager.mensagemDeDriverAusente(URL_CH);
        assertNotNull(mensagemCh);
        assertTrue(mensagemCh.contains("com.clickhouse.jdbc.ClickHouseDriver"), mensagemCh);
        assertTrue(mensagemCh.contains(URL_CH), mensagemCh);
        assertTrue(mensagemCh.contains("clickhouse-jdbc"), mensagemCh);
        assertTrue(mensagemCh.contains("ClickHouse"), mensagemCh);

        String mensagemOracle = ConexaoSupplier.DoDriverManager.mensagemDeDriverAusente(URL_ORACLE);
        assertNotNull(mensagemOracle);
        assertTrue(mensagemOracle.contains("oracle.jdbc.OracleDriver"), mensagemOracle);
        assertTrue(mensagemOracle.contains("ojdbc8"), mensagemOracle);
    }

    @Test
    @DisplayName("o prefixo é reconhecido sem depender de caixa nem de espaços")
    void reconhecimentoTolerante() {
        assertTrue(ConexaoSupplier.DoDriverManager
                .mensagemDeDriverAusente("  JDBC:CH://localhost:8123/x  ") != null);
        assertTrue(ConexaoSupplier.DoDriverManager
                .mensagemDeDriverAusente("JDBC:Oracle:thin:@//h:1521/S") != null);
    }
}
