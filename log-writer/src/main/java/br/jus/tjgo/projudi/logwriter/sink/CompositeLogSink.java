package br.jus.tjgo.projudi.logwriter.sink;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import br.jus.tjgo.projudi.logwriter.LogRegistro;
import br.jus.tjgo.projudi.logwriter.LogSink;
import br.jus.tjgo.projudi.logwriter.LogWriterException;
import br.jus.tjgo.projudi.logwriter.Metricas;

/**
 * Escrita dupla — o modo {@code AMBOS} da feature flag.
 *
 * <p>Serve ao período de sombra em homologação: os dois destinos recebem o mesmo
 * registro, com o mesmo {@code ID_LOG}, o que permite comparar Oracle e
 * ClickHouse linha a linha por chave, e não por amostragem. Numa migração de log
 * de auditoria, essa comparação é a evidência que justifica desligar o destino
 * antigo.</p>
 *
 * <h3>Política de falha parcial</h3>
 *
 * <p>Todos os sinks são tentados sempre — a falha de um não impede os outros.
 * A exceção só é propagada se <b>todos</b> falharem, porque um registro que
 * chegou a pelo menos um destino não está perdido. Falha parcial vira aviso no
 * log: em modo sombra, um dos lados ficar para trás é exatamente a informação
 * que se quer observar, não motivo para abortar.</p>
 */
public final class CompositeLogSink implements LogSink {

    private static final Logger LOG = Logger.getLogger(CompositeLogSink.class.getName());

    private final List<LogSink> sinks;
    private final Metricas metricas = new Metricas();

    public CompositeLogSink(LogSink... sinks) {
        this(Arrays.asList(sinks));
    }

    public CompositeLogSink(List<LogSink> sinks) {
        if (sinks == null || sinks.isEmpty()) {
            throw new IllegalArgumentException("é preciso pelo menos um sink");
        }
        List<LogSink> copia = new ArrayList<LogSink>(sinks.size());
        for (LogSink sink : sinks) {
            if (sink == null) {
                throw new IllegalArgumentException("sink nulo na composição");
            }
            copia.add(sink);
        }
        this.sinks = copia;
    }

    @Override
    public void escrever(LogRegistro registro) throws LogWriterException {
        metricas.somarRecebidos(1L);
        int sucessos = 0;
        LogWriterException ultimaFalha = null;

        for (LogSink sink : sinks) {
            try {
                sink.escrever(registro);
                sucessos++;
            } catch (LogWriterException e) {
                ultimaFalha = e;
                LOG.log(Level.WARNING, "Escrita dupla: " + sink + " falhou; os demais destinos seguem", e);
            }
        }
        contabilizar(1, sucessos, ultimaFalha);
    }

    @Override
    public void escreverLote(List<LogRegistro> registros) throws LogWriterException {
        if (registros == null || registros.isEmpty()) {
            return;
        }
        metricas.somarRecebidos(registros.size());
        int sucessos = 0;
        LogWriterException ultimaFalha = null;

        for (LogSink sink : sinks) {
            try {
                sink.escreverLote(registros);
                sucessos++;
            } catch (LogWriterException e) {
                ultimaFalha = e;
                LOG.log(Level.WARNING, "Escrita dupla: " + sink + " falhou; os demais destinos seguem", e);
            }
        }
        contabilizar(registros.size(), sucessos, ultimaFalha);
    }

    private void contabilizar(int quantidade, int sucessos, LogWriterException ultimaFalha)
            throws LogWriterException {
        if (sucessos > 0) {
            metricas.somarGravadosDestino(quantidade);
            metricas.somarLotesGravados(1L);
        }
        if (sucessos < sinks.size()) {
            metricas.somarLotesComFalha(1L);
        }
        if (sucessos == 0) {
            metricas.somarPerdidos(quantidade);
            throw new LogWriterException(
                    "Escrita dupla falhou em todos os " + sinks.size() + " destinos", ultimaFalha);
        }
    }

    @Override
    public void flush() throws LogWriterException {
        LogWriterException ultimaFalha = null;
        for (LogSink sink : sinks) {
            try {
                sink.flush();
            } catch (LogWriterException e) {
                ultimaFalha = e;
            }
        }
        if (ultimaFalha != null) {
            throw ultimaFalha;
        }
    }

    @Override
    public Metricas metricas() {
        return metricas;
    }

    /** Os sinks compostos, para inspeção de métricas individuais. */
    public List<LogSink> getSinks() {
        return new ArrayList<LogSink>(sinks);
    }

    @Override
    public void close() {
        for (LogSink sink : sinks) {
            try {
                sink.close();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Erro ao fechar " + sink, e);
            }
        }
    }

    @Override
    public String toString() {
        return "CompositeLogSink" + sinks;
    }
}
