package br.jus.tjgo.projudi.logwriter.sink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import br.jus.tjgo.projudi.logwriter.LogRegistro;
import br.jus.tjgo.projudi.logwriter.LogSink;
import br.jus.tjgo.projudi.logwriter.LogWriterConfig;
import br.jus.tjgo.projudi.logwriter.LogWriterException;
import br.jus.tjgo.projudi.logwriter.Metricas;

/**
 * Fila limitada em memória, gravação em lote por thread própria, e desvio para
 * um sink de fallback em toda situação anormal.
 *
 * <p>É o sink que a LogPs enxerga, e o único lugar onde mora a decisão mais
 * pesada da Frente B (docs/decisoes.md, decisão 19).</p>
 *
 * <h3>O que este sink garante — e o que não garante</h3>
 *
 * <ul>
 *   <li><b>Garante:</b> nenhum registro se perde por indisponibilidade do
 *       ClickHouse. Lote que falha no destino vai para o fallback (Oracle).</li>
 *   <li><b>Garante:</b> a thread do usuário nunca bloqueia esperando o
 *       ClickHouse, e nunca recebe exceção por causa de log.</li>
 *   <li><b>Garante:</b> a fila não cresce sem limite. Fila cheia desvia para o
 *       fallback na hora, na própria thread chamadora — o mesmo custo que o
 *       Projudi já paga hoje, não uma regressão.</li>
 *   <li><b>Não garante:</b> durabilidade contra morte abrupta da JVM (kill -9,
 *       OOM, queda de energia). O que estiver em memória e ainda não gravado se
 *       perde. <b>A janela é de no máximo
 *       {@code fila.capacidade + lote.max} registros</b> — com os padrões,
 *       10.000 + 500 = 10.500 — ou, em regime, o que tiver entrado nos últimos
 *       {@code lote.intervaloMs} milissegundos (padrão: 1.000 ms).</li>
 * </ul>
 *
 * <p>Encerramento ordenado ({@link #close()}) drena a fila e fecha a janela. Em
 * webapp, chame-o de um {@code ServletContextListener.contextDestroyed}; o
 * shutdown hook da JVM é opcional e vem desligado, porque um hook segurando
 * referência ao sink impede a coleta do classloader da aplicação no undeploy do
 * Tomcat.</p>
 *
 * <h3>Sobre a semântica de transação</h3>
 *
 * <p>Hoje o log grava na mesma conexão e na mesma transação da operação de
 * negócio: rollback do negócio desfaz o log junto. Com este sink, a gravação
 * acontece fora daquela transação, então <b>um log pode chegar ao ClickHouse
 * mesmo que a operação de negócio sofra rollback depois</b>. É uma diferença
 * semântica real, registrada como limitação conhecida em docs/decisoes.md,
 * decisão 19.</p>
 */
public final class BufferedLogSink implements LogSink {

    private static final Logger LOG = Logger.getLogger(BufferedLogSink.class.getName());

    /**
     * Logger dedicado ao desvio para o Oracle. Separado de propósito: durante a
     * transição, "quantos logs foram pelo caminho velho" é métrica operacional,
     * e ter um nome de logger próprio permite roteá-la sem filtrar texto.
     */
    private static final Logger LOG_FALLBACK = Logger.getLogger("projudi.logwriter.FALLBACK");

    /** Teto de espera do laço externo, para que close() responda rápido. */
    private static final long ESPERA_OCIOSA_MS = 200L;

    private final LogSink destino;
    private final LogSink fallback;
    private final BlockingQueue<LogRegistro> fila;
    private final int loteMax;
    private final long intervaloFlushMs;
    private final int tentativas;
    private final Metricas metricas = new Metricas();

    private final Thread flusher;
    private volatile boolean ativo = true;

    public BufferedLogSink(LogSink destino, LogSink fallback, LogWriterConfig config) {
        this(destino, fallback,
                config.getFilaCapacidade(),
                config.getLoteMax(),
                config.getIntervaloFlushMs(),
                config.getTentativas());
    }

    public BufferedLogSink(LogSink destino, LogSink fallback,
                           int filaCapacidade, int loteMax, long intervaloFlushMs, int tentativas) {
        if (destino == null) {
            throw new IllegalArgumentException("destino não pode ser nulo");
        }
        if (filaCapacidade < 1 || loteMax < 1 || intervaloFlushMs < 1 || tentativas < 1) {
            throw new IllegalArgumentException(
                    "filaCapacidade, loteMax, intervaloFlushMs e tentativas precisam ser >= 1");
        }
        this.destino = destino;
        this.fallback = fallback;
        this.fila = new ArrayBlockingQueue<LogRegistro>(filaCapacidade);
        this.loteMax = loteMax;
        this.intervaloFlushMs = intervaloFlushMs;
        this.tentativas = tentativas;

        if (fallback == null) {
            LOG.warning("BufferedLogSink sem fallback: falha do destino significa PERDA de log de auditoria. "
                    + "Aceitável em teste e no harness de benchmark; não em produção.");
        }

        this.flusher = new Thread(new Laco(), "projudi-log-writer-flush");
        this.flusher.setDaemon(true);
        this.flusher.start();
    }

    /**
     * Enfileira o registro. <b>Nunca bloqueia e nunca lança.</b>
     *
     * <p>Fila cheia significa que o ClickHouse não está dando conta do ritmo de
     * entrada. Nesse caso o registro vai para o fallback imediatamente, na
     * thread chamadora — a única alternativa que não é nem descartar em
     * silêncio nem travar o usuário.</p>
     */
    @Override
    public void escrever(LogRegistro registro) {
        if (registro == null) {
            return;
        }
        metricas.somarRecebidos(1L);

        if (!ativo) {
            // Sink já encerrado: nada de aceitar na fila que ninguém vai drenar.
            metricas.somarDesviosPorSaturacao(1L);
            enviarAoFallback(Collections.singletonList(registro), "sink encerrado");
            return;
        }

        if (!fila.offer(registro)) {
            metricas.somarDesviosPorSaturacao(1L);
            enviarAoFallback(Collections.singletonList(registro),
                    "fila cheia (" + fila.size() + "/" + capacidadeTotal() + ")");
        }
    }

    @Override
    public void escreverLote(List<LogRegistro> registros) {
        if (registros == null) {
            return;
        }
        for (LogRegistro registro : registros) {
            escrever(registro);
        }
    }

    /**
     * Drena e grava o que estiver na fila neste instante, na thread chamadora.
     * Usado por testes, pelo harness e por {@link #close()}.
     */
    @Override
    public void flush() {
        List<LogRegistro> lote = new ArrayList<LogRegistro>(loteMax);
        while (true) {
            lote.clear();
            fila.drainTo(lote, loteMax);
            if (lote.isEmpty()) {
                return;
            }
            gravarLote(new ArrayList<LogRegistro>(lote));
        }
    }

    /** Registros enfileirados ainda não gravados. */
    public int pendentes() {
        return fila.size();
    }

    public int capacidadeTotal() {
        return fila.size() + fila.remainingCapacity();
    }

    @Override
    public Metricas metricas() {
        return metricas;
    }

    /**
     * Para de aceitar, drena o que restou e fecha destino e fallback. Idempotente.
     */
    @Override
    public void close() {
        if (!ativo) {
            return;
        }
        ativo = false;
        try {
            // O laço sai sozinho quando !ativo e a fila esvazia.
            flusher.join(Math.max(5000L, intervaloFlushMs * 3L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        flush();

        fecharSilencioso(destino);
        fecharSilencioso(fallback);

        LOG.log(Level.INFO, "log-writer encerrado. {0}", metricas.resumo());
    }

    private static void fecharSilencioso(LogSink sink) {
        if (sink != null) {
            try {
                sink.close();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Erro ao fechar sink", e);
            }
        }
    }

    // -------------------------------------------------------------------------

    private final class Laco implements Runnable {

        @Override
        public void run() {
            List<LogRegistro> lote = new ArrayList<LogRegistro>(loteMax);
            while (ativo || !fila.isEmpty()) {
                try {
                    LogRegistro primeiro = fila.poll(
                            Math.min(ESPERA_OCIOSA_MS, intervaloFlushMs), TimeUnit.MILLISECONDS);
                    if (primeiro == null) {
                        continue;
                    }
                    lote.clear();
                    lote.add(primeiro);
                    completarLote(lote);
                    gravarLote(new ArrayList<LogRegistro>(lote));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RuntimeException e) {
                    // Nada pode matar esta thread: sem ela, a fila enche e todo
                    // o tráfego passa a cair no fallback silenciosamente.
                    LOG.log(Level.SEVERE, "Erro inesperado no laço de flush do log-writer", e);
                }
            }
        }

        /**
         * Completa o lote até {@code loteMax} ou até vencer
         * {@code intervaloFlushMs} contado a partir do primeiro registro. É o
         * que dá sentido ao número: nenhum registro espera mais que isso na
         * fila em regime normal.
         */
        private void completarLote(List<LogRegistro> lote) throws InterruptedException {
            long prazoNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(intervaloFlushMs);
            // A espera é fatiada para que close() não fique refém do intervalo
            // de flush: encerrando, o lote parcial sai na hora.
            long fatiaNs = TimeUnit.MILLISECONDS.toNanos(Math.min(ESPERA_OCIOSA_MS, intervaloFlushMs));

            while (lote.size() < loteMax) {
                fila.drainTo(lote, loteMax - lote.size());
                if (lote.size() >= loteMax || !ativo) {
                    return;
                }
                long restanteNs = prazoNs - System.nanoTime();
                if (restanteNs <= 0L) {
                    return;
                }
                LogRegistro proximo = fila.poll(Math.min(restanteNs, fatiaNs), TimeUnit.NANOSECONDS);
                if (proximo != null) {
                    lote.add(proximo);
                }
            }
        }
    }

    private void gravarLote(List<LogRegistro> lote) {
        if (lote.isEmpty()) {
            return;
        }
        LogWriterException ultimaFalha = null;
        for (int tentativa = 1; tentativa <= tentativas; tentativa++) {
            try {
                destino.escreverLote(lote);
                metricas.somarGravadosDestino(lote.size());
                metricas.somarLotesGravados(1L);
                return;
            } catch (LogWriterException e) {
                ultimaFalha = e;
                metricas.somarLotesComFalha(1L);
                if (tentativa < tentativas) {
                    LOG.log(Level.WARNING, "Tentativa " + tentativa + "/" + tentativas
                            + " de gravar lote de " + lote.size() + " registro(s) falhou; reenviando", e);
                }
            } catch (RuntimeException e) {
                ultimaFalha = new LogWriterException("Erro não verificado ao gravar lote", e);
                metricas.somarLotesComFalha(1L);
                break;
            }
        }

        metricas.somarDesviosPorFalha(lote.size());
        LOG.log(Level.WARNING, "Destino indisponível após " + tentativas
                + " tentativa(s); desviando " + lote.size() + " registro(s) para o fallback", ultimaFalha);
        enviarAoFallback(lote, "destino indisponível");
    }

    private void enviarAoFallback(List<LogRegistro> lote, String motivo) {
        if (fallback == null) {
            metricas.somarPerdidos(lote.size());
            LOG.log(Level.SEVERE,
                    "PERDA DE LOG DE AUDITORIA: {0} registro(s) descartado(s) ({1}) — não há fallback configurado.",
                    new Object[]{Integer.valueOf(lote.size()), motivo});
            return;
        }
        try {
            fallback.escreverLote(lote);
            metricas.somarGravadosFallback(lote.size());
            LOG_FALLBACK.log(Level.INFO,
                    "{0} registro(s) de log gravado(s) pelo caminho legado (Oracle). Motivo: {1}. Acumulado: {2}.",
                    new Object[]{Integer.valueOf(lote.size()), motivo,
                            Long.valueOf(metricas.getGravadosFallback())});
        } catch (LogWriterException e) {
            metricas.somarPerdidos(lote.size());
            LOG.log(Level.SEVERE, "PERDA DE LOG DE AUDITORIA: " + lote.size()
                    + " registro(s) falharam no destino E no fallback (" + motivo + ")", e);
        } catch (RuntimeException e) {
            metricas.somarPerdidos(lote.size());
            LOG.log(Level.SEVERE, "PERDA DE LOG DE AUDITORIA: " + lote.size()
                    + " registro(s) — erro não verificado no fallback (" + motivo + ")", e);
        }
    }

    @Override
    public String toString() {
        return "BufferedLogSink[destino=" + destino
                + ", fallback=" + fallback
                + ", fila=" + fila.size() + "/" + capacidadeTotal()
                + ", loteMax=" + loteMax
                + ", intervaloFlushMs=" + intervaloFlushMs + "]";
    }
}
