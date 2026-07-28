package br.jus.tjgo.projudi.logwriter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.UnsupportedEncodingException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import br.jus.tjgo.projudi.logwriter.apoio.PayloadsReais;
import br.jus.tjgo.projudi.logwriter.sink.BufferedLogSink;
import br.jus.tjgo.projudi.logwriter.sink.ClickHouseLogSink;

/**
 * Ida e volta contra o ClickHouse de verdade: grava os três formatos reais de
 * payload e lê de volta, exigindo <b>igualdade byte a byte</b>.
 *
 * <p>Pulado por padrão. Precisa do ambiente de pé e de uma propriedade
 * explícita:</p>
 *
 * <pre>
 * make up-lite
 * mvn test -Dclickhouse.integracao=true
 * </pre>
 *
 * <h3>Por que byte a byte e não {@code equals} de String</h3>
 *
 * <p>A origem do log no Projudi é uma base Oracle em Latin-1 e o destino é
 * UTF-8. Uma comparação de {@code String} passa mesmo com uma normalização
 * Unicode no meio do caminho (NFC/NFD), que muda os bytes e quebraria qualquer
 * conferência posterior de hash ou de tamanho. O teste compara os
 * <b>bytes UTF-8</b> dos dois lados, e por isso detecta a diferença.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "clickhouse.integracao", matches = "true")
class ClickHouseIntegracaoIT {

    private static final String TABELA = "projudi_logs.log_raw";

    private ConexaoSupplier conexoes;
    private IdGerador gerador;

    @BeforeAll
    void prepararAmbiente() throws Exception {
        String url = propriedade("clickhouse.url", "jdbc:ch://localhost:8123/projudi_logs");
        String usuario = propriedade("clickhouse.usuario", "projudi_app");
        String senha = propriedade("clickhouse.senha", "projudi_app_dev");

        conexoes = new ConexaoSupplier.DoDriverManager(url, usuario, senha);
        gerador = new IdGerador(1023L); // worker reservado ao teste de integração

        Connection cx = conexoes.obter();
        try {
            Statement st = cx.createStatement();
            try {
                st.execute("SELECT 1");
            } finally {
                st.close();
            }
        } finally {
            cx.close();
        }
    }

    @AfterAll
    void limparRastros() throws Exception {
        // Só o que este teste escreveu: identificado pelo workerId 1023.
        executar("ALTER TABLE " + TABELA + " DELETE WHERE bitAnd(bitShiftRight(ID_LOG, 12), 1023) = 1023");
    }

    private static String propriedade(String chave, String padrao) {
        String valor = System.getProperty(chave);
        return (valor == null || valor.trim().isEmpty()) ? padrao : valor.trim();
    }

    // -------------------------------------------------------------------------

    @Test
    @DisplayName("os três formatos reais voltam byte a byte idênticos, com acentuação preservada")
    void idaEVoltaDosTresFormatos() throws Exception {
        Map<Long, String[]> esperado = new LinkedHashMap<Long, String[]>();
        List<LogRegistro> registros = new ArrayList<LogRegistro>();

        String[][] casos = {
                // formato 1: [campo:valor;...] vindo de getPropriedades()
                {PayloadsReais.PROPRIEDADES_ANTERIOR, PayloadsReais.PROPRIEDADES},
                // formato 2: JSON com sufixo [Origem:...] montado por setOrigem
                {"", PayloadsReais.JSON_COM_ORIGEM},
                // formato 3: texto livre, o caminho do inserirErro
                {"", PayloadsReais.TEXTO_LIVRE},
                // caso-limite: aspas, barras, quebras de linha, tabs, símbolos
                {PayloadsReais.CARACTERES_DIFICEIS, PayloadsReais.CARACTERES_DIFICEIS}
        };

        for (String[] caso : casos) {
            long id = gerador.proximo();
            esperado.put(Long.valueOf(id), caso);
            registros.add(LogRegistro.novo()
                    .idLog(id)
                    .idLogTipo(44L)
                    .idUsuario(998877L)
                    .ipComputador("10.20.30.40")
                    .hora(new Date())
                    .tabela("Processo")
                    .idTabela("104620234")
                    .valorAtual(caso[0])
                    .valorNovo(caso[1])
                    .codigoTemp("54321")
                    .construir());
        }

        ClickHouseLogSink sink = new ClickHouseLogSink(conexoes);
        try {
            sink.escreverLote(registros);
        } finally {
            sink.close();
        }

        for (Map.Entry<Long, String[]> entrada : esperado.entrySet()) {
            long id = entrada.getKey().longValue();
            String[] caso = entrada.getValue();
            String[] lido = lerValores(id);

            assertTrue(lido != null, "registro ID_LOG=" + id + " não foi encontrado na log_raw");
            assertIdenticoEmBytes(caso[0], lido[0], "VALOR_ATUAL do ID_LOG=" + id);
            assertIdenticoEmBytes(caso[1], lido[1], "VALOR_NOVO do ID_LOG=" + id);
        }
    }

    @Test
    @DisplayName("acentuação e cedilha sobrevivem à ida e volta, caractere a caractere")
    void acentuacaoPreservada() throws Exception {
        // Cobre o repertório que a base Latin-1 do Projudi produz, mais alguns
        // símbolos que existem em Latin-1 e são fáceis de perder na conversão.
        String acentos = "áàâãäéèêëíìîïóòôõöúùûüçÁÀÂÃÄÉÈÊËÍÌÎÏÓÒÔÕÖÚÙÛÜÇñÑ"
                + "ªº°±µ¼½¾§¶·¿¡«»×÷ßÿýÝþðÐ";

        long id = gerador.proximo();
        LogRegistro registro = LogRegistro.novo()
                .idLog(id)
                .idLogTipo(44L)
                .idUsuario(1L)
                .hora(new Date())
                .tabela("Serventia")
                .valorAtual(acentos)
                .valorNovo("[Serventia:1ª Vara Cível de Goiânia;Órgão:Núcleo;Origem:TesteAcentos]")
                .construir();

        ClickHouseLogSink sink = new ClickHouseLogSink(conexoes);
        try {
            sink.escrever(registro);
        } finally {
            sink.close();
        }

        String[] lido = lerValores(id);
        assertTrue(lido != null, "registro não encontrado");
        assertEquals(acentos.length(), lido[0].length(),
                "o comprimento em caracteres mudou — houve conversão de encoding no caminho");
        assertIdenticoEmBytes(acentos, lido[0], "repertório acentuado");
        assertIdenticoEmBytes("[Serventia:1ª Vara Cível de Goiânia;Órgão:Núcleo;Origem:TesteAcentos]",
                lido[1], "payload acentuado no formato de propriedades");
    }

    @Test
    @DisplayName("as 13 colunas voltam com os valores gravados, incluindo os Nullable")
    void todasAsColunas() throws Exception {
        long id = gerador.proximo();
        Date hora = new Date((System.currentTimeMillis() / 1000L) * 1000L); // DateTime tem precisão de segundo

        LogRegistro registro = LogRegistro.novo()
                .idLog(id)
                .idLogTipo(44L)
                .idUsuario(998877L)
                .ipComputador("192.168.100.200")
                .hora(hora)
                .tabela("ProcessoParte")
                .idTabela("777888999")
                .valorAtual(PayloadsReais.PROPRIEDADES_ANTERIOR)
                .valorNovo(PayloadsReais.PROPRIEDADES)
                .codigoTemp("54321")
                .hash("d41d8cd98f00b204e9800998ecf8427e")
                .qtdErrosDia(Integer.valueOf(7))
                .construir();

        ClickHouseLogSink sink = new ClickHouseLogSink(conexoes);
        try {
            sink.escrever(registro);
        } finally {
            sink.close();
        }

        Connection cx = conexoes.obter();
        try {
            PreparedStatement ps = cx.prepareStatement(
                    "SELECT ID_LOG_TIPO, ID_USU, IP_COMPUTADOR, DATA, HORA, TABELA, "
                            + "CODIGO_TEMP, ID_TABELA, HASH, QTD_ERROS_DIA "
                            + "FROM " + TABELA + " WHERE ID_LOG = ?");
            try {
                ps.setLong(1, id);
                ResultSet rs = ps.executeQuery();
                try {
                    assertTrue(rs.next(), "registro não encontrado");
                    assertEquals(44L, rs.getLong("ID_LOG_TIPO"));
                    assertEquals(998877L, rs.getLong("ID_USU"));
                    assertEquals("192.168.100.200", rs.getString("IP_COMPUTADOR"));
                    assertEquals("ProcessoParte", rs.getString("TABELA"));
                    assertEquals(54321L, rs.getLong("CODIGO_TEMP"));
                    assertEquals(777888999L, rs.getLong("ID_TABELA"));
                    assertEquals("d41d8cd98f00b204e9800998ecf8427e", rs.getString("HASH").trim());
                    assertEquals(7, rs.getInt("QTD_ERROS_DIA"));
                    assertEquals(hora.getTime() / 1000L, rs.getTimestamp("HORA").getTime() / 1000L,
                            "HORA precisa voltar com a mesma precisão de segundo do DateTime");
                } finally {
                    rs.close();
                }
            } finally {
                ps.close();
            }
        } finally {
            cx.close();
        }
    }

    @Test
    @DisplayName("o caminho completo com fila e lote entrega tudo, sem perder nem duplicar")
    void caminhoCompletoComFila() throws Exception {
        int quantidade = 2000;
        List<Long> ids = new ArrayList<Long>(quantidade);
        String[] payloads = PayloadsReais.todos();

        BufferedLogSink sink = new BufferedLogSink(
                new ClickHouseLogSink(conexoes), null, 5000, 250, 200L, 2);
        try {
            for (int i = 0; i < quantidade; i++) {
                long id = gerador.proximo();
                ids.add(Long.valueOf(id));
                sink.escrever(LogRegistro.novo()
                        .idLog(id)
                        .idLogTipo(44L)
                        .idUsuario(900000L + i)
                        .hora(new Date())
                        .tabela("Movimentacao")
                        .idTabela(String.valueOf(i))
                        .valorNovo(payloads[i % payloads.length])
                        .construir());
            }
        } finally {
            sink.close(); // drena antes de conferir
        }

        assertEquals(0L, sink.metricas().getPerdidos());
        assertEquals(quantidade, (int) sink.metricas().getGravadosDestino());

        long menor = ids.get(0).longValue();
        long maior = ids.get(ids.size() - 1).longValue();

        Connection cx = conexoes.obter();
        try {
            Statement st = cx.createStatement();
            try {
                ResultSet rs = st.executeQuery(
                        "SELECT count() AS linhas, uniqExact(ID_LOG) AS distintos FROM " + TABELA
                                + " WHERE ID_LOG BETWEEN " + menor + " AND " + maior);
                try {
                    assertTrue(rs.next());
                    assertEquals(quantidade, rs.getLong("linhas"), "faltaram ou sobraram linhas");
                    assertEquals(quantidade, rs.getLong("distintos"), "houve ID_LOG duplicado");
                } finally {
                    rs.close();
                }
            } finally {
                st.close();
            }
        } finally {
            cx.close();
        }
    }

    // -------------------------------------------------------------------------

    /** @return {@code {VALOR_ATUAL, VALOR_NOVO}} ou {@code null} se não achou. */
    private String[] lerValores(long idLog) throws SQLException {
        Connection cx = conexoes.obter();
        try {
            PreparedStatement ps = cx.prepareStatement(
                    "SELECT VALOR_ATUAL, VALOR_NOVO FROM " + TABELA + " WHERE ID_LOG = ?");
            try {
                ps.setLong(1, idLog);
                ResultSet rs = ps.executeQuery();
                try {
                    if (!rs.next()) {
                        return null;
                    }
                    return new String[]{rs.getString(1), rs.getString(2)};
                } finally {
                    rs.close();
                }
            } finally {
                ps.close();
            }
        } finally {
            cx.close();
        }
    }

    private void executar(String sql) throws SQLException {
        Connection cx = conexoes.obter();
        try {
            Statement st = cx.createStatement();
            try {
                st.execute(sql);
            } finally {
                st.close();
            }
        } finally {
            cx.close();
        }
    }

    private static void assertIdenticoEmBytes(String esperado, String obtido, String contexto) {
        assertEquals(esperado, obtido, contexto + ": diferença de conteúdo");
        try {
            assertArrayEquals(esperado.getBytes("UTF-8"), obtido.getBytes("UTF-8"),
                    contexto + ": os bytes UTF-8 diferem (normalização Unicode no caminho?)");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 sempre existe numa JVM", e);
        }
    }
}
