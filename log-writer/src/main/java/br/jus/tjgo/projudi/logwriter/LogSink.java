package br.jus.tjgo.projudi.logwriter;

import java.io.Closeable;
import java.util.List;

/**
 * Destino de gravação de log — <b>a fronteira que substitui o INSERT da LogPs</b>.
 *
 * <p>Hoje a {@code LogPs.inserir(LogDt)} monta um INSERT dinâmico e chama
 * {@code executarInsert(sql, "ID_LOG", ps)}. Essa é a única coisa que a Solução 1
 * precisa trocar. Tudo o que a LogPs faz antes disso — montar VALOR_ATUAL,
 * sortear o CODIGO_TEMP, truncar a TABELA — continua igual.</p>
 *
 * <p>Implementações desta interface são <b>componíveis</b>, e é dessa composição
 * que saem os três estados da feature flag:</p>
 *
 * <pre>
 *   ORACLE      → o log-writer nem entra em cena (LogDestino.ativo() == false)
 *   CLICKHOUSE  → BufferedLogSink( ClickHouseLogSink, fallback = Oracle )
 *   AMBOS       → CompositeLogSink( acima, OracleLogSink )
 * </pre>
 *
 * <h3>Contrato de falha</h3>
 *
 * <p>{@code escrever} declara {@link LogWriterException}, mas o sink que a LogPs
 * enxerga — o {@code BufferedLogSink} — <b>não lança</b>: ele desvia para o
 * fallback e contabiliza. Gravar log nunca pode derrubar uma operação de
 * negócio. A exceção existe para os sinks folha, onde a falha é real e precisa
 * ser tratada por quem os compõe.</p>
 *
 * <p>Implementações devem ser seguras para uso por várias threads de request.</p>
 */
public interface LogSink extends Closeable {

    /** Grava um registro. O ID_LOG já vem preenchido. */
    void escrever(LogRegistro registro) throws LogWriterException;

    /**
     * Grava um lote. O padrão itera, e é o suficiente para sinks que não têm
     * noção de lote; {@code ClickHouseLogSink} e {@code OracleLogSink}
     * sobrescrevem com {@code addBatch}/{@code executeBatch}.
     */
    default void escreverLote(List<LogRegistro> registros) throws LogWriterException {
        for (LogRegistro registro : registros) {
            escrever(registro);
        }
    }

    /** Força a saída do que estiver pendente. Sinks sem buffer não fazem nada. */
    default void flush() throws LogWriterException {
        // sem buffer: nada a fazer
    }

    /** Contadores deste sink. Nunca nulo. */
    Metricas metricas();

    /** Drena o pendente e libera recursos. Não lança. */
    @Override
    void close();
}
