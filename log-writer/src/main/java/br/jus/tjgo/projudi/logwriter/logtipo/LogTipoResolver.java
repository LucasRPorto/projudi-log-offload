package br.jus.tjgo.projudi.logwriter.logtipo;

/**
 * Traduz {@code LOG_TIPO_CODIGO} (código de negócio) em {@code ID_LOG_TIPO}
 * (chave da dimensão).
 *
 * <p>Existe porque a {@code LogPs.inserir} tem dois caminhos para essa coluna:
 * ou a {@code LogDt} já traz o {@code ID_LOG_TIPO}, ou o INSERT resolve na hora
 * com um subselect —
 * {@code (SELECT MAX(ID_LOG_TIPO) FROM PROJUDI.LOG_TIPO WHERE LOG_TIPO_CODIGO = ?)}.
 * O segundo caminho é o comum: a maior parte dos chamadores constrói a LogDt
 * com {@code logTipoCodigo}, não com o id.</p>
 *
 * <p><b>A resolução acontece no ClickHouse, não no Oracle.</b> Manter o
 * subselect no Oracle significaria uma ida ao banco transacional a cada log —
 * justamente o que a Solução 1 existe para eliminar. A dimensão
 * {@code projudi_logs.log_tipo} já é espelhada no ClickHouse (decisão 13) e
 * muda raramente, então uma consulta por código distinto, cacheada para sempre,
 * é suficiente.</p>
 */
public interface LogTipoResolver {

    /**
     * @param logTipoCodigo código de negócio
     * @return o {@code ID_LOG_TIPO} correspondente, ou {@code 0} se não houver
     *         correspondência — nunca lança. Um tipo de log desconhecido não é
     *         motivo para perder o registro de auditoria inteiro; ele é gravado
     *         com {@code ID_LOG_TIPO = 0} e fica detectável por consulta.
     */
    long resolver(long logTipoCodigo);

    /**
     * Não resolve nada: devolve {@code 0} sempre. É o padrão de quem já entrega
     * o {@code ID_LOG_TIPO} pronto, e o usado pelo harness de benchmark, que
     * não deve medir latência de dimensão.
     */
    LogTipoResolver INERTE = new LogTipoResolver() {
        @Override
        public long resolver(long logTipoCodigo) {
            return 0L;
        }

        @Override
        public String toString() {
            return "LogTipoResolver.INERTE";
        }
    };
}
