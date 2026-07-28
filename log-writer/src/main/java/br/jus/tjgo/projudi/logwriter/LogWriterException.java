package br.jus.tjgo.projudi.logwriter;

/**
 * Falha na gravação de um registro de log.
 *
 * <p>É uma exceção verificada de propósito: quem escreve um sink novo precisa
 * decidir explicitamente o que fazer com a falha. No caminho que a LogPs usa,
 * porém, ela nunca escapa — o {@link br.jus.tjgo.projudi.logwriter.sink.BufferedLogSink}
 * a converte em desvio para o fallback, para que log quebrado nunca derrube uma
 * operação de negócio.</p>
 */
public class LogWriterException extends Exception {

    private static final long serialVersionUID = 1L;

    public LogWriterException(String mensagem) {
        super(mensagem);
    }

    public LogWriterException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
