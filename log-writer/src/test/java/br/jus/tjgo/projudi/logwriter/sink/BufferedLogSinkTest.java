package br.jus.tjgo.projudi.logwriter.sink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import br.jus.tjgo.projudi.logwriter.LogRegistro;
import br.jus.tjgo.projudi.logwriter.LogSink;
import br.jus.tjgo.projudi.logwriter.LogWriterException;
import br.jus.tjgo.projudi.logwriter.apoio.PayloadsReais;

/**
 * Verifica as garantias que a decisão 19 do docs/decisoes.md promete. Cada teste
 * corresponde a uma frase daquele registro.
 */
class BufferedLogSinkTest {

    private static LogRegistro registro(long id) {
        return LogRegistro.novo()
                .idLog(id)
                .idUsuario(4321L)
                .tabela("Processo")
                .idTabela(String.valueOf(id))
                .valorNovo(PayloadsReais.PROPRIEDADES)
                .construir();
    }

    private static void esperarAte(Condicao condicao, long limiteMs) throws InterruptedException {
        long fim = System.currentTimeMillis() + limiteMs;
        while (System.currentTimeMillis() < fim) {
            if (condicao.satisfeita()) {
                return;
            }
            Thread.sleep(5L);
        }
        assertTrue(condicao.satisfeita(), "condição não satisfeita em " + limiteMs + " ms");
    }

    private interface Condicao {
        boolean satisfeita();
    }

    @Test
    @Timeout(30)
    @DisplayName("no caminho feliz tudo chega ao destino, agrupado em lotes")
    void caminhoFeliz() throws Exception {
        final MemoriaLogSink destino = new MemoriaLogSink();
        MemoriaLogSink fallback = new MemoriaLogSink();
        BufferedLogSink sink = new BufferedLogSink(destino, fallback, 1000, 50, 50L, 2);

        try {
            for (int i = 1; i <= 500; i++) {
                sink.escrever(registro(i));
            }
            esperarAte(new Condicao() {
                @Override
                public boolean satisfeita() {
                    return destino.quantidade() == 500;
                }
            }, 10_000L);

            assertEquals(500, destino.quantidade());
            assertEquals(0, fallback.quantidade(), "o fallback não devia ter sido acionado");
            assertEquals(500L, sink.metricas().getRecebidos());
            assertEquals(500L, sink.metricas().getGravadosDestino());
            assertEquals(0L, sink.metricas().getPerdidos());
            assertTrue(sink.metricas().getLotesGravados() < 500L,
                    "500 registros com loteMax=50 precisam sair em bem menos de 500 lotes; "
                            + "saíram em " + sink.metricas().getLotesGravados());
        } finally {
            sink.close();
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("destino indisponível desvia o lote para o fallback, sem perder nada")
    void destinoIndisponivelDesviaParaFallback() throws Exception {
        MemoriaLogSink destino = new MemoriaLogSink("ClickHouse fora do ar");
        final MemoriaLogSink fallback = new MemoriaLogSink();
        BufferedLogSink sink = new BufferedLogSink(destino, fallback, 1000, 10, 20L, 2);

        try {
            for (int i = 1; i <= 100; i++) {
                sink.escrever(registro(i));
            }
            esperarAte(new Condicao() {
                @Override
                public boolean satisfeita() {
                    return fallback.quantidade() == 100;
                }
            }, 10_000L);

            assertEquals(100, fallback.quantidade());
            assertEquals(0, destino.quantidade());
            assertEquals(100L, sink.metricas().getGravadosFallback());
            assertEquals(100L, sink.metricas().getDesviosPorFalha());
            assertEquals(0L, sink.metricas().getPerdidos(), "nada pode se perder com fallback de pé");
            assertTrue(sink.metricas().getLotesComFalha() > 0L);
        } finally {
            sink.close();
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("o destino volta a ser usado assim que se recupera")
    void recuperacaoDoDestino() throws Exception {
        MemoriaLogSink destino = new MemoriaLogSink("indisponível");
        final MemoriaLogSink fallback = new MemoriaLogSink();
        final BufferedLogSink sink = new BufferedLogSink(destino, fallback, 1000, 5, 20L, 1);

        try {
            for (int i = 1; i <= 20; i++) {
                sink.escrever(registro(i));
            }
            esperarAte(new Condicao() {
                @Override
                public boolean satisfeita() {
                    return fallback.quantidade() == 20;
                }
            }, 10_000L);

            destino.simularFalha(null);
            for (int i = 21; i <= 40; i++) {
                sink.escrever(registro(i));
            }
            final MemoriaLogSink destinoFinal = destino;
            esperarAte(new Condicao() {
                @Override
                public boolean satisfeita() {
                    return destinoFinal.quantidade() == 20;
                }
            }, 10_000L);

            assertEquals(20, destino.quantidade());
            assertEquals(20, fallback.quantidade(), "o fallback não pode continuar recebendo");
        } finally {
            sink.close();
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("fila cheia drena para o fallback, sem crescer e sem descartar")
    void filaCheiaDrenaParaFallback() throws Exception {
        // O destino trava dentro do escreverLote, então a fila enche de verdade.
        final CountDownLatch liberar = new CountDownLatch(1);
        final MemoriaLogSink registrados = new MemoriaLogSink();
        LogSink destinoLento = new LogSinkDelegado(registrados) {
            @Override
            public void escreverLote(List<LogRegistro> lote) throws LogWriterException {
                try {
                    liberar.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                super.escreverLote(lote);
            }
        };
        MemoriaLogSink fallback = new MemoriaLogSink();
        BufferedLogSink sink = new BufferedLogSink(destinoLento, fallback, 10, 5, 20L, 1);

        try {
            for (int i = 1; i <= 200; i++) {
                sink.escrever(registro(i));
            }

            assertTrue(sink.pendentes() <= sink.capacidadeTotal(),
                    "a fila cresceu além da capacidade declarada");
            assertTrue(fallback.quantidade() > 0,
                    "com a fila cheia, alguém tinha que ter ido para o fallback");
            assertEquals(0L, sink.metricas().getPerdidos(), "nada pode ser descartado em silêncio");
            assertEquals(fallback.quantidade(), sink.metricas().getGravadosFallback());
            assertTrue(sink.metricas().getDesviosPorSaturacao() > 0L,
                    "o desvio por saturação precisa ser contabilizado separadamente");

            liberar.countDown();
        } finally {
            liberar.countDown();
            sink.close();
        }

        // Nada se perdeu: destino + fallback = tudo o que entrou.
        assertEquals(200L, sink.metricas().getRecebidos());
        assertEquals(200, registrados.quantidade() + fallback.quantidade(),
                "a soma de destino e fallback precisa fechar com o que entrou");
        assertEquals(0L, sink.metricas().getPerdidos());
    }

    @Test
    @Timeout(30)
    @DisplayName("escrever nunca lança, mesmo com destino e fallback quebrados")
    void escreverNuncaLanca() throws Exception {
        MemoriaLogSink destino = new MemoriaLogSink("destino quebrado");
        MemoriaLogSink fallback = new MemoriaLogSink("fallback quebrado");
        BufferedLogSink sink = new BufferedLogSink(destino, fallback, 100, 5, 20L, 1);

        try {
            for (int i = 1; i <= 50; i++) {
                sink.escrever(registro(i)); // não declara exceção: é o contrato
            }
            final BufferedLogSink alvo = sink;
            esperarAte(new Condicao() {
                @Override
                public boolean satisfeita() {
                    return alvo.metricas().getPerdidos() == 50L;
                }
            }, 10_000L);

            // Perda com os DOIS destinos fora é o único caminho de perda, e
            // precisa ser visível — é o número que o operador monitora.
            assertEquals(50L, sink.metricas().getPerdidos());
        } finally {
            sink.close();
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("close drena a fila: nada fica para trás no encerramento ordenado")
    void closeDrenaAFila() throws Exception {
        MemoriaLogSink destino = new MemoriaLogSink();
        MemoriaLogSink fallback = new MemoriaLogSink();
        // Intervalo longo de propósito: sem o dreno do close, ficaria pendente.
        BufferedLogSink sink = new BufferedLogSink(destino, fallback, 10_000, 5000, 30_000L, 1);

        for (int i = 1; i <= 1000; i++) {
            sink.escrever(registro(i));
        }
        sink.close();

        assertEquals(1000, destino.quantidade(), "close precisa drenar tudo o que estava na fila");
        assertEquals(0, sink.pendentes());
        assertEquals(0L, sink.metricas().getPerdidos());
        assertTrue(destino.isFechado(), "close precisa fechar o destino");
        assertTrue(fallback.isFechado(), "close precisa fechar o fallback");
    }

    @Test
    @Timeout(60)
    @DisplayName("com muitas threads concorrentes, nenhum registro se perde nem se duplica")
    void concorrencia() throws Exception {
        final MemoriaLogSink destino = new MemoriaLogSink();
        MemoriaLogSink fallback = new MemoriaLogSink();
        final BufferedLogSink sink = new BufferedLogSink(destino, fallback, 5000, 200, 20L, 2);

        final int threads = 8;
        final int porThread = 1000;
        final CountDownLatch largada = new CountDownLatch(1);
        final CountDownLatch chegada = new CountDownLatch(threads);
        List<Thread> lista = new ArrayList<Thread>();

        for (int t = 0; t < threads; t++) {
            final int base = t * porThread + 1;
            Thread th = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        largada.await();
                        for (int i = 0; i < porThread; i++) {
                            sink.escrever(registro(base + i));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        chegada.countDown();
                    }
                }
            }, "carga-" + t);
            lista.add(th);
            th.start();
        }
        largada.countDown();
        assertTrue(chegada.await(45, TimeUnit.SECONDS), "as threads de carga não terminaram");
        sink.close();

        int total = threads * porThread;
        assertEquals(total, destino.quantidade() + fallback.quantidade());
        assertEquals(0L, sink.metricas().getPerdidos());

        Set<Long> ids = new HashSet<Long>();
        for (LogRegistro r : destino.getRegistros()) {
            assertTrue(ids.add(Long.valueOf(r.getIdLog())), "registro duplicado: " + r.getIdLog());
        }
        for (LogRegistro r : fallback.getRegistros()) {
            assertTrue(ids.add(Long.valueOf(r.getIdLog())), "registro duplicado: " + r.getIdLog());
        }
        assertEquals(total, ids.size());
    }

    /** Base para sobrescrever só um método do MemoriaLogSink. */
    private static class LogSinkDelegado implements LogSink {

        private final MemoriaLogSink alvo;

        LogSinkDelegado(MemoriaLogSink alvo) {
            this.alvo = alvo;
        }

        @Override
        public void escrever(LogRegistro registro) throws LogWriterException {
            alvo.escrever(registro);
        }

        @Override
        public void escreverLote(List<LogRegistro> lote) throws LogWriterException {
            alvo.escreverLote(lote);
        }

        @Override
        public br.jus.tjgo.projudi.logwriter.Metricas metricas() {
            return alvo.metricas();
        }

        @Override
        public void close() {
            alvo.close();
        }
    }
}
