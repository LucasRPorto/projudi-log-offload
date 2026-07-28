package br.jus.tjgo.projudi.logwriter;

/**
 * Estados da feature flag que decide para onde a LogPs grava.
 *
 * <p>O padrão é {@link #ORACLE}: sem nenhuma configuração, a biblioteca se
 * declara inativa e o Projudi segue exatamente como hoje. Ligar a Solução 1 é
 * um ato explícito; esquecer de configurar não muda comportamento nenhum.</p>
 */
public enum LogDestino {

    /** Caminho legado. O log-writer não assume a escrita. */
    ORACLE,

    /** Só ClickHouse. O Oracle continua existindo como fallback de falha. */
    CLICKHOUSE,

    /**
     * Escrita dupla (modo sombra). Grava nos dois destinos para permitir a
     * comparação registro a registro em homologação, antes de confiar apenas no
     * ClickHouse. Ver docs/decisoes.md, decisão 21.
     *
     * <p><b>Divisão de responsabilidade:</b> a biblioteca grava apenas no
     * ClickHouse; a cópia no Oracle é feita pelo chamador, executando o mesmo
     * código que ele executa hoje. Não é preguiça de composição — é o que
     * mantém a cópia Oracle dentro da transação de negócio, como sempre foi, e
     * garante que o modo sombra compare o ClickHouse contra o comportamento
     * real de produção, e não contra uma reimplementação dele. Daí
     * {@link #gravaNoOracle()}, que a LogPs consulta para decidir se cai no
     * caminho legado depois de chamar o writer.</p>
     */
    AMBOS;

    /** {@code true} quando o log-writer deve assumir a gravação. */
    public boolean ativo() {
        return this != ORACLE;
    }

    /** {@code true} quando o Oracle também recebe a escrita no caminho feliz. */
    public boolean gravaNoOracle() {
        return this == AMBOS;
    }

    /**
     * Converte o texto da configuração, tolerando caixa e espaços. Valor
     * ausente, vazio ou desconhecido resolve para {@link #ORACLE} — a falha
     * segura é não mexer no comportamento atual.
     */
    public static LogDestino de(String valor) {
        if (valor == null) {
            return ORACLE;
        }
        String limpo = valor.trim().toUpperCase();
        for (LogDestino d : values()) {
            if (d.name().equals(limpo)) {
                return d;
            }
        }
        return ORACLE;
    }
}
