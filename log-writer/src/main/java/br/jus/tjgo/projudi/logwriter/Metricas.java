package br.jus.tjgo.projudi.logwriter;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Contadores da gravação de log.
 *
 * <p>Existe por exigência operacional da transição, não por completude: enquanto
 * o Projudi estiver com a flag ligada, <b>"quantos logs foram pelo caminho
 * velho"</b> é a métrica que diz se o ClickHouse está sustentando a carga. Sem
 * ela, um ClickHouse intermitente vira um desvio silencioso para o Oracle, e o
 * ganho medido no relatório não corresponde ao que aconteceu de fato.</p>
 *
 * <p>Todos os contadores são monotônicos e acumulam desde a criação do sink.</p>
 */
public final class Metricas {

    private final AtomicLong recebidos = new AtomicLong();
    private final AtomicLong gravadosDestino = new AtomicLong();
    private final AtomicLong gravadosFallback = new AtomicLong();
    private final AtomicLong desviosPorSaturacao = new AtomicLong();
    private final AtomicLong desviosPorFalha = new AtomicLong();
    private final AtomicLong lotesComFalha = new AtomicLong();
    private final AtomicLong perdidos = new AtomicLong();
    private final AtomicLong lotesGravados = new AtomicLong();
    private final AtomicLong nanosEmFlush = new AtomicLong();

    /** Registros entregues pela aplicação ao sink. */
    public long getRecebidos() {
        return recebidos.get();
    }

    /** Registros efetivamente gravados no destino principal (ClickHouse). */
    public long getGravadosDestino() {
        return gravadosDestino.get();
    }

    /** Registros gravados pelo fallback (Oracle). */
    public long getGravadosFallback() {
        return gravadosFallback.get();
    }

    /** Desvios ao fallback causados por fila cheia. */
    public long getDesviosPorSaturacao() {
        return desviosPorSaturacao.get();
    }

    /** Desvios ao fallback causados por falha do destino principal. */
    public long getDesviosPorFalha() {
        return desviosPorFalha.get();
    }

    /** Lotes que falharam no destino principal (antes do reenvio). */
    public long getLotesComFalha() {
        return lotesComFalha.get();
    }

    /**
     * Registros que não chegaram a lugar nenhum: falharam no destino <b>e</b> no
     * fallback. É o único caminho de perda com o sink ativo, e por isso o número
     * que precisa ser zero.
     */
    public long getPerdidos() {
        return perdidos.get();
    }

    public long getLotesGravados() {
        return lotesGravados.get();
    }

    public long getNanosEmFlush() {
        return nanosEmFlush.get();
    }

    // ---- mutadores usados pelas implementacoes de LogSink ------------------
    // Publicos porque os sinks vivem no subpacote .sink e o Java 8 nao tem
    // modulos; nao fazem parte da API que a aplicacao consome.

    public void somarRecebidos(long n) {
        recebidos.addAndGet(n);
    }

    public void somarGravadosDestino(long n) {
        gravadosDestino.addAndGet(n);
    }

    public void somarGravadosFallback(long n) {
        gravadosFallback.addAndGet(n);
    }

    public void somarDesviosPorSaturacao(long n) {
        desviosPorSaturacao.addAndGet(n);
    }

    public void somarDesviosPorFalha(long n) {
        desviosPorFalha.addAndGet(n);
    }

    public void somarLotesComFalha(long n) {
        lotesComFalha.addAndGet(n);
    }

    public void somarPerdidos(long n) {
        perdidos.addAndGet(n);
    }

    public void somarLotesGravados(long n) {
        lotesGravados.addAndGet(n);
    }

    public void somarNanosEmFlush(long n) {
        nanosEmFlush.addAndGet(n);
    }

    /** Linha única, própria para ir a um log operacional periódico. */
    public String resumo() {
        return "log-writer[recebidos=" + getRecebidos()
                + " destino=" + getGravadosDestino()
                + " fallback=" + getGravadosFallback()
                + " (saturacao=" + getDesviosPorSaturacao()
                + " falha=" + getDesviosPorFalha() + ")"
                + " lotes=" + getLotesGravados()
                + " lotesComFalha=" + getLotesComFalha()
                + " PERDIDOS=" + getPerdidos() + "]";
    }

    @Override
    public String toString() {
        return resumo();
    }
}
