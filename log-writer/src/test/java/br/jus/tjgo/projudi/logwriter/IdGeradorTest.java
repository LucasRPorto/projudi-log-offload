package br.jus.tjgo.projudi.logwriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IdGeradorTest {

    /** Relógio controlável: só avança quando o teste mandar. */
    private static final class RelogioFixo implements IdGerador.Relogio {
        private final AtomicLong agora;

        RelogioFixo(long inicial) {
            this.agora = new AtomicLong(inicial);
        }

        @Override
        public long agoraMs() {
            return agora.get();
        }

        void avancar(long ms) {
            agora.addAndGet(ms);
        }
    }

    private static long instante(String iso) {
        java.text.SimpleDateFormat formato =
                new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        formato.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        try {
            return formato.parse(iso).getTime();
        } catch (java.text.ParseException e) {
            throw new IllegalArgumentException(iso, e);
        }
    }

    @Test
    @DisplayName("IDs de uma mesma JVM são únicos e estritamente crescentes")
    void unicidadeEMonotonicidade() {
        IdGerador gerador = new IdGerador(7L);
        Set<Long> vistos = new HashSet<Long>();
        long anterior = Long.MIN_VALUE;

        for (int i = 0; i < 200_000; i++) {
            long id = gerador.proximo();
            assertTrue(vistos.add(Long.valueOf(id)), "ID repetido na iteração " + i + ": " + id);
            assertTrue(id > anterior, "ID não crescente na iteração " + i);
            anterior = id;
        }
    }

    @Test
    @DisplayName("dois workers no mesmo milissegundo não colidem")
    void doisWorkersNoMesmoMilissegundo() throws Exception {
        // O mesmo relógio para os dois: força a colisão a depender só do workerId.
        RelogioFixo relogio = new RelogioFixo(instante("2026-07-27T10:00:00Z"));
        final IdGerador a = new IdGerador(1L, relogio);
        final IdGerador b = new IdGerador(2L, relogio);

        final int porWorker = 2000;
        final List<Long> deA = Collections.synchronizedList(new ArrayList<Long>());
        final List<Long> deB = Collections.synchronizedList(new ArrayList<Long>());
        final CountDownLatch largada = new CountDownLatch(1);
        final CountDownLatch chegada = new CountDownLatch(2);

        Runnable tarefaA = new Runnable() {
            @Override
            public void run() {
                try {
                    largada.await();
                    for (int i = 0; i < porWorker; i++) {
                        deA.add(Long.valueOf(a.proximo()));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    chegada.countDown();
                }
            }
        };
        Runnable tarefaB = new Runnable() {
            @Override
            public void run() {
                try {
                    largada.await();
                    for (int i = 0; i < porWorker; i++) {
                        deB.add(Long.valueOf(b.proximo()));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    chegada.countDown();
                }
            }
        };

        new Thread(tarefaA, "worker-1").start();
        new Thread(tarefaB, "worker-2").start();
        largada.countDown();
        assertTrue(chegada.await(30, TimeUnit.SECONDS), "os workers não terminaram a tempo");

        Set<Long> todos = new HashSet<Long>();
        todos.addAll(deA);
        todos.addAll(deB);
        assertEquals(porWorker * 2, todos.size(), "houve colisão entre os dois workers");

        for (Long id : deA) {
            assertEquals(1L, IdGerador.workerDe(id.longValue()));
        }
        for (Long id : deB) {
            assertEquals(2L, IdGerador.workerDe(id.longValue()));
        }
    }

    @Test
    @DisplayName("estourar os 4096 slots do milissegundo espera o próximo, nunca reutiliza")
    void estouroDaSequenciaEsperaProximoMilissegundo() {
        final RelogioFixo relogio = new RelogioFixo(instante("2026-07-27T10:00:00Z"));
        IdGerador gerador = new IdGerador(3L, relogio);

        // 4096 IDs cabem no milissegundo corrente...
        Set<Long> vistos = new HashSet<Long>();
        for (int i = 0; i < 4096; i++) {
            assertTrue(vistos.add(Long.valueOf(gerador.proximo())), "repetiu no slot " + i);
        }
        long ultimoDoMs = instante("2026-07-27T10:00:00Z");
        for (Long id : vistos) {
            assertEquals(ultimoDoMs, IdGerador.instanteDe(id.longValue()));
        }

        // ...e o 4097º precisa do milissegundo seguinte. Uma thread avança o
        // relógio para que o busy-wait termine; sem ela, o teste travaria — que
        // é exatamente a garantia sendo verificada.
        Thread empurra = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                relogio.avancar(1L);
            }
        });
        empurra.start();

        long id4097 = gerador.proximo();
        assertTrue(vistos.add(Long.valueOf(id4097)), "o 4097º ID repetiu um anterior");
        assertEquals(ultimoDoMs + 1L, IdGerador.instanteDe(id4097),
                "o 4097º ID deveria cair no milissegundo seguinte");
        assertEquals(0L, IdGerador.sequenciaDe(id4097), "a sequência deveria reiniciar em 0");
    }

    @Test
    @DisplayName("a faixa gerada é disjunta da numeração legada do Oracle")
    void faixaDisjuntaDaNumeracaoLegada() {
        // LOG_ID_LOG_SEQ de produção (BancoDeDados/01_CreateSequence.sql)
        // começa em 104.620.234. Um ID gerado aqui precisa estar ordens de
        // grandeza acima, para que a migração nunca colida.
        long maiorIdLegadoPlausivel = 10_000_000_000L; // 100x a sequence atual
        long id = new IdGerador(0L).proximo();
        assertTrue(id > maiorIdLegadoPlausivel,
                "ID gerado (" + id + ") precisa ficar acima da faixa legada");
        assertTrue(id > 0L, "ID precisa ser positivo para caber em UInt64 sem surpresa");
    }

    @Test
    @DisplayName("os três campos do ID são decodificáveis")
    void decodificacao() {
        RelogioFixo relogio = new RelogioFixo(instante("2026-07-27T13:45:12Z"));
        IdGerador gerador = new IdGerador(511L, relogio);

        long primeiro = gerador.proximo();
        long segundo = gerador.proximo();

        assertEquals(instante("2026-07-27T13:45:12Z"), IdGerador.instanteDe(primeiro));
        assertEquals(511L, IdGerador.workerDe(primeiro));
        assertEquals(0L, IdGerador.sequenciaDe(primeiro));
        assertEquals(1L, IdGerador.sequenciaDe(segundo));
        assertNotEquals(primeiro, segundo);
    }

    @Test
    @DisplayName("workerId fora de 0..1023 é rejeitado na construção")
    void workerIdForaDaFaixa() {
        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                new IdGerador(1024L);
            }
        });
        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                new IdGerador(-1L);
            }
        });
    }

    @Test
    @DisplayName("workerId configurado é respeitado; ausente, é derivado e cabe na faixa")
    void resolucaoDoWorkerId() {
        assertEquals(42L, IdGerador.resolverWorkerId(Long.valueOf(42L)));

        long derivado = IdGerador.resolverWorkerId(null);
        assertTrue(derivado >= 0L && derivado <= IdGerador.MAX_WORKER_ID,
                "workerId derivado fora da faixa: " + derivado);
        // Determinístico dentro do mesmo processo: duas chamadas dão o mesmo valor.
        assertEquals(derivado, IdGerador.resolverWorkerId(null));

        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                IdGerador.resolverWorkerId(Long.valueOf(5000L));
            }
        });
    }

    @Test
    @DisplayName("relógio que retrocede não produz ID repetido")
    void relogioQueRetrocede() {
        final RelogioFixo relogio = new RelogioFixo(instante("2026-07-27T10:00:00Z"));
        IdGerador gerador = new IdGerador(9L, relogio);

        Set<Long> vistos = new HashSet<Long>();
        for (int i = 0; i < 10; i++) {
            relogio.avancar(1L);
            vistos.add(Long.valueOf(gerador.proximo()));
        }
        long antesDoRetrocesso = relogio.agoraMs();

        // Ajuste de NTP joga o relógio 5 ms para trás; uma thread o traz de
        // volta, simulando o tempo real passando durante a espera.
        relogio.avancar(-5L);
        Thread empurra = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                relogio.avancar(6L);
            }
        });
        empurra.start();

        long depois = gerador.proximo();
        assertTrue(vistos.add(Long.valueOf(depois)), "ID repetido após retrocesso do relógio");
        assertTrue(IdGerador.instanteDe(depois) >= antesDoRetrocesso,
                "o gerador não pode emitir com instante anterior ao último já emitido");
    }
}
