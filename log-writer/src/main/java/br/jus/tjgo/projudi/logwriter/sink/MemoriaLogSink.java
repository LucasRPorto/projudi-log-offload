package br.jus.tjgo.projudi.logwriter.sink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import br.jus.tjgo.projudi.logwriter.LogRegistro;
import br.jus.tjgo.projudi.logwriter.LogSink;
import br.jus.tjgo.projudi.logwriter.LogWriterException;
import br.jus.tjgo.projudi.logwriter.Metricas;

/**
 * Sink que guarda tudo numa lista.
 *
 * <p>É a abstração testável que permite exercitar fila, lote, fallback e feature
 * flag <b>sem ClickHouse nem Oracle de pé</b> — requisito de {@code mvn test}
 * verde em qualquer máquina.</p>
 *
 * <p>Fica em {@code src/main} e não em {@code src/test} de propósito: também
 * serve para um smoke test dentro do próprio Projudi (ligar a flag apontando
 * para memória prova que a LogPs chama a fronteira certa, sem gravar nada em
 * lugar nenhum).</p>
 */
public final class MemoriaLogSink implements LogSink {

    private final List<LogRegistro> registros =
            Collections.synchronizedList(new ArrayList<LogRegistro>());
    private final Metricas metricas = new Metricas();

    /** Quando != null, toda escrita falha com esta mensagem. */
    private volatile String falhaSimulada;
    private volatile boolean fechado;

    public MemoriaLogSink() {
        this(null);
    }

    public MemoriaLogSink(String falhaSimulada) {
        this.falhaSimulada = falhaSimulada;
    }

    /** Liga/desliga a falha simulada — usado para exercitar o fallback. */
    public void simularFalha(String mensagem) {
        this.falhaSimulada = mensagem;
    }

    @Override
    public void escrever(LogRegistro registro) throws LogWriterException {
        escreverLote(Collections.singletonList(registro));
    }

    @Override
    public void escreverLote(List<LogRegistro> lote) throws LogWriterException {
        String falha = falhaSimulada;
        if (falha != null) {
            metricas.somarLotesComFalha(1L);
            throw new LogWriterException(falha);
        }
        metricas.somarRecebidos(lote.size());
        registros.addAll(lote);
        metricas.somarGravadosDestino(lote.size());
        metricas.somarLotesGravados(1L);
    }

    /** Cópia do que foi gravado, na ordem de chegada. */
    public List<LogRegistro> getRegistros() {
        synchronized (registros) {
            return new ArrayList<LogRegistro>(registros);
        }
    }

    public int quantidade() {
        return registros.size();
    }

    public void limpar() {
        registros.clear();
    }

    public boolean isFechado() {
        return fechado;
    }

    @Override
    public Metricas metricas() {
        return metricas;
    }

    @Override
    public void close() {
        fechado = true;
    }

    @Override
    public String toString() {
        return "MemoriaLogSink[" + registros.size() + " registro(s)"
                + (falhaSimulada == null ? "" : ", FALHANDO: " + falhaSimulada) + "]";
    }
}
