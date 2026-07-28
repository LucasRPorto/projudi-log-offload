package br.jus.tjgo.projudi.logwriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import br.jus.tjgo.projudi.logwriter.apoio.PayloadsReais;
import br.jus.tjgo.projudi.logwriter.sink.CompositeLogSink;
import br.jus.tjgo.projudi.logwriter.sink.MemoriaLogSink;

/**
 * Exercita a fronteira que a LogPs vai chamar, incluindo os três estados da
 * feature flag.
 */
class LogWriterTest {

    @AfterEach
    void limpar() {
        LogWriter.encerrar();
        System.clearProperty(LogWriterConfig.P_DESTINO);
        System.clearProperty(LogWriterConfig.P_LOTE_MAX);
        System.clearProperty(LogWriterConfig.P_WORKER_ID);
    }

    private static LogRegistro registro() {
        return LogRegistro.novo()
                .tabela("Processo")
                .idTabela("104620234")
                .idUsuario("998877")
                .ipComputador("10.20.30.40")
                .logTipoCodigo("28")
                .valorAtual(PayloadsReais.PROPRIEDADES_ANTERIOR)
                .valorNovo(PayloadsReais.PROPRIEDADES)
                .codigoTemp("54321")
                .construir();
    }

    @Test
    @DisplayName("sem configuração, a biblioteca fica inerte e o Projudi grava como sempre")
    void inativoPorPadrao() {
        LogWriter.configurar(LogWriterConfig.doAmbiente(), null);
        final LogWriter writer = LogWriter.instancia();

        assertEquals(LogDestino.ORACLE, writer.destino());
        assertFalse(writer.ativo());
        assertFalse(writer.destino().ativo());
        assertThrows(IllegalStateException.class, new Executable() {
            @Override
            public void execute() {
                writer.inserir(registro());
            }
        });
    }

    @Test
    @DisplayName("destino desconhecido cai em ORACLE, não em exceção")
    void destinoDesconhecidoFalhaSeguro() {
        assertEquals(LogDestino.ORACLE, LogDestino.de("CASSANDRA"));
        assertEquals(LogDestino.ORACLE, LogDestino.de(null));
        assertEquals(LogDestino.ORACLE, LogDestino.de(""));
        assertEquals(LogDestino.CLICKHOUSE, LogDestino.de("  clickhouse  "));
        assertEquals(LogDestino.AMBOS, LogDestino.de("Ambos"));
    }

    @Test
    @DisplayName("com CLICKHOUSE, o registro é gravado e o ID volta preenchido")
    void gravaEDevolveOId() {
        MemoriaLogSink destino = new MemoriaLogSink();
        LogWriter.configurar(LogWriterConfig.padrao().destino(LogDestino.CLICKHOUSE), destino, 5L);
        LogWriter writer = LogWriter.instancia();

        assertTrue(writer.ativo());
        long id = writer.inserir(registro());

        assertNotEquals(0L, id, "o ID precisa voltar para dados.setId(...) da LogPs");
        assertEquals(5L, IdGerador.workerDe(id));
        assertEquals(1, destino.quantidade());
        assertEquals(id, destino.getRegistros().get(0).getIdLog());

        // O modo CLICKHOUSE não pede ao chamador que grave no Oracle.
        assertFalse(writer.destino().gravaNoOracle());
    }

    @Test
    @DisplayName("ID já preenchido é respeitado; ausente, é gerado")
    void respeitaIdPreexistente() {
        MemoriaLogSink destino = new MemoriaLogSink();
        LogWriter.configurar(LogWriterConfig.padrao().destino(LogDestino.CLICKHOUSE), destino, 1L);
        LogWriter writer = LogWriter.instancia();

        long informado = 999_999_999_999L;
        assertEquals(informado, writer.inserir(LogRegistro.novo().idLog(informado).construir()));

        long gerado = writer.inserir(registro());
        assertNotEquals(0L, gerado);
        assertNotEquals(informado, gerado);
    }

    @Test
    @DisplayName("IDs de chamadas seguidas são únicos e crescentes")
    void idsUnicosEmSequencia() {
        MemoriaLogSink destino = new MemoriaLogSink();
        LogWriter.configurar(LogWriterConfig.padrao().destino(LogDestino.CLICKHOUSE), destino, 0L);
        LogWriter writer = LogWriter.instancia();

        long anterior = 0L;
        for (int i = 0; i < 10_000; i++) {
            long id = writer.inserir(registro());
            assertTrue(id > anterior, "IDs precisam ser crescentes");
            anterior = id;
        }
        assertEquals(10_000, destino.quantidade());
    }

    @Test
    @DisplayName("AMBOS pede ao chamador que também grave no Oracle")
    void modoAmbos() {
        MemoriaLogSink destino = new MemoriaLogSink();
        LogWriter.configurar(LogWriterConfig.padrao().destino(LogDestino.AMBOS), destino, 3L);
        LogWriter writer = LogWriter.instancia();

        assertTrue(writer.ativo());
        assertTrue(writer.destino().gravaNoOracle(),
                "no modo sombra a LogPs cai no caminho legado depois de chamar o writer");

        long id = writer.inserir(registro());
        assertEquals(1, destino.quantidade());
        assertEquals(id, destino.getRegistros().get(0).getIdLog(),
                "os dois destinos precisam receber o MESMO ID_LOG, senão a comparação "
                        + "registro a registro do modo sombra não fecha por chave");
    }

    @Test
    @DisplayName("escrita dupla componível: os dois destinos recebem o mesmo registro")
    void escritaDuplaComposta() throws Exception {
        MemoriaLogSink clickHouse = new MemoriaLogSink();
        MemoriaLogSink oracle = new MemoriaLogSink();
        CompositeLogSink composto = new CompositeLogSink(clickHouse, oracle);

        LogWriter.configurar(LogWriterConfig.padrao().destino(LogDestino.AMBOS), composto, 4L);
        long id = LogWriter.instancia().inserir(registro());

        assertEquals(1, clickHouse.quantidade());
        assertEquals(1, oracle.quantidade());
        assertEquals(id, clickHouse.getRegistros().get(0).getIdLog());
        assertEquals(id, oracle.getRegistros().get(0).getIdLog());
    }

    @Test
    @DisplayName("na escrita dupla, um destino fora não impede o outro")
    void escritaDuplaComFalhaParcial() throws Exception {
        MemoriaLogSink clickHouse = new MemoriaLogSink("ClickHouse fora");
        MemoriaLogSink oracle = new MemoriaLogSink();
        CompositeLogSink composto = new CompositeLogSink(clickHouse, oracle);

        composto.escrever(registro());

        assertEquals(0, clickHouse.quantidade());
        assertEquals(1, oracle.quantidade(), "o destino saudável precisa receber mesmo assim");
        assertEquals(0L, composto.metricas().getPerdidos());
        assertEquals(1L, composto.metricas().getLotesComFalha());
    }

    @Test
    @DisplayName("na escrita dupla, os dois fora propagam a falha")
    void escritaDuplaComTudoFora() {
        MemoriaLogSink a = new MemoriaLogSink("fora");
        MemoriaLogSink b = new MemoriaLogSink("fora");
        final CompositeLogSink composto = new CompositeLogSink(a, b);

        assertThrows(LogWriterException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                composto.escrever(registro());
            }
        });
        assertEquals(1L, composto.metricas().getPerdidos());
    }

    @Test
    @DisplayName("o adaptador partindo da LogDt preenche as 13 colunas")
    void adaptacaoDaLogDt() {
        MemoriaLogSink destino = new MemoriaLogSink();
        LogWriter.configurar(LogWriterConfig.padrao().destino(LogDestino.CLICKHOUSE), destino, 2L);

        long id = LogWriter.instancia().inserir(registro());

        List<LogRegistro> gravados = destino.getRegistros();
        assertEquals(1, gravados.size());
        LogRegistro r = gravados.get(0);

        assertEquals(id, r.getIdLog());
        assertEquals("Processo", r.getTabela());
        assertEquals(Long.valueOf(104620234L), r.getIdTabela());
        assertEquals(998877L, r.getIdUsu());
        assertEquals("10.20.30.40", r.getIpComputador());
        assertEquals(28L, r.getLogTipoCodigo());
        assertEquals(PayloadsReais.PROPRIEDADES_ANTERIOR, r.getValorAtual());
        assertEquals(PayloadsReais.PROPRIEDADES, r.getValorNovo());
        assertEquals(Long.valueOf(54321L), r.getCodigoTemp());
        assertTrue(r.getHora().getTime() > 0L);
        assertTrue(r.getData().getTime() > 0L);
    }

    @Test
    @DisplayName("encerrar drena e é idempotente")
    void encerrarDrenaEEIdempotente() {
        MemoriaLogSink destino = new MemoriaLogSink();
        LogWriter.configurar(LogWriterConfig.padrao().destino(LogDestino.CLICKHOUSE), destino, 6L);
        LogWriter.instancia().inserir(registro());

        LogWriter.encerrar();
        LogWriter.encerrar();

        assertTrue(destino.isFechado());
        assertEquals(1, destino.quantidade());
    }

    @Test
    @DisplayName("a configuração é lida de system property")
    void configuracaoPorSystemProperty() {
        System.setProperty(LogWriterConfig.P_DESTINO, "CLICKHOUSE");
        System.setProperty(LogWriterConfig.P_LOTE_MAX, "77");
        System.setProperty(LogWriterConfig.P_WORKER_ID, "13");

        LogWriterConfig config = LogWriterConfig.doAmbiente();

        assertEquals(LogDestino.CLICKHOUSE, config.getDestino());
        assertEquals(77, config.getLoteMax());
        assertEquals(Long.valueOf(13L), config.getWorkerId());
        assertEquals(LogWriterConfig.URL_PADRAO, config.getClickHouseUrl());
        assertEquals(LogWriterConfig.USUARIO_PADRAO, config.getClickHouseUsuario());
    }

    @Test
    @DisplayName("valor inválido na configuração cai no padrão, sem derrubar a subida")
    void configuracaoInvalidaUsaPadrao() {
        System.setProperty(LogWriterConfig.P_LOTE_MAX, "quinhentos");
        assertEquals(500, LogWriterConfig.doAmbiente().getLoteMax());
    }

    @Test
    @DisplayName("o nome da variável de ambiente é derivado da system property")
    void nomeDeVariavelDeAmbiente() {
        assertEquals("PROJUDI_LOGWRITER_DESTINO",
                LogWriterConfig.nomeDeAmbiente(LogWriterConfig.P_DESTINO));
        assertEquals("PROJUDI_LOGWRITER_LOTE_INTERVALO_MS",
                LogWriterConfig.nomeDeAmbiente(LogWriterConfig.P_LOTE_INTERVALO));
        assertEquals("PROJUDI_LOGWRITER_WORKER_ID",
                LogWriterConfig.nomeDeAmbiente(LogWriterConfig.P_WORKER_ID));
        assertEquals("PROJUDI_LOGWRITER_CLICKHOUSE_URL",
                LogWriterConfig.nomeDeAmbiente(LogWriterConfig.P_CH_URL));
    }
}
