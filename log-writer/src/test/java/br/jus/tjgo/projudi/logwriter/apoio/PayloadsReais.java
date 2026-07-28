package br.jus.tjgo.projudi.logwriter.apoio;

/**
 * Os três formatos que a {@code LogPs} realmente grava nos CLOBs
 * {@code VALOR_ATUAL} / {@code VALOR_NOVO}.
 *
 * <p>Não são exemplos inventados: cada um corresponde a um caminho identificado
 * na leitura do código do Projudi.</p>
 *
 * <ol>
 *   <li><b>{@code [campo:valor;...]}</b> — saída de
 *       {@code Dados.getPropriedades()}, que cada {@code *Dt} implementa. É o
 *       formato dominante, e o que {@code LogDt.getListaAtributos()} e
 *       {@code mostrarDiferencaTextoLog()} sabem interpretar de volta.</li>
 *   <li><b>JSON com sufixo {@code [Origem:...]}</b> — resultado de
 *       {@code LogDt.setOrigem(String)} sobre um valor que não termina em
 *       {@code ']'}: o método concatena {@code " [Origem:" + origem + "]"}.
 *       Como todo JSON termina em <code>}</code>, esse é sempre o caminho que
 *       um payload JSON percorre.</li>
 *   <li><b>Texto livre</b> — mensagens de erro e descrições, o caminho do
 *       {@code inserirErro(LogErroDt)}.</li>
 * </ol>
 *
 * <p>Todos carregam acentuação e cedilha de propósito. A origem no Oracle é
 * Latin-1 e o destino no ClickHouse é UTF-8; se a conversão estivesse errada em
 * algum ponto do caminho, é aqui que apareceria.</p>
 */
public final class PayloadsReais {

    private PayloadsReais() {
    }

    /** Formato 1, com o {@code ;Origem:} inserido antes do ']' final. */
    public static final String PROPRIEDADES =
            "[Id_Proc:104620234;ProcNumero:5015678-90.2024.8.09.0051;Serventia:1ª Vara Cível "
                    + "da Comarca de Goiânia;Classificação:Execução de Título Extrajudicial;"
                    + "Valor:R$ 12.480,55;Situação:Em tramitação;Órgão:Núcleo de Conciliação;"
                    + "Observação:Petição inicial não instruída — intimação expedida;"
                    + "Origem:ProcessoNe.alterarSituacao]";

    /** Formato 1 no estado "antes", para o par VALOR_ATUAL / VALOR_NOVO. */
    public static final String PROPRIEDADES_ANTERIOR =
            "[Id_Proc:104620234;ProcNumero:5015678-90.2024.8.09.0051;Serventia:1ª Vara Cível "
                    + "da Comarca de Goiânia;Classificação:Execução de Título Extrajudicial;"
                    + "Valor:R$ 12.480,55;Situação:Suspenso;Órgão:Núcleo de Conciliação;"
                    + "Observação:Aguardando manifestação da parte exequente;"
                    + "Origem:ProcessoNe.alterarSituacao]";

    /** Formato 2: JSON seguido do sufixo de origem, exatamente como setOrigem monta. */
    public static final String JSON_COM_ORIGEM =
            "{\"idMovimentacao\":88213347,\"tipo\":\"Juntada de Petição\","
                    + "\"descricao\":\"Petição de habilitação de advogado — OAB/GO 12.345\","
                    + "\"partes\":[{\"nome\":\"Construções e Reformas São Sebastião Ltda\","
                    + "\"papel\":\"Exequente\"},{\"nome\":\"João Antônio Gonçalves Assunção\","
                    + "\"papel\":\"Executado\"}],\"sigiloso\":false,"
                    + "\"observacao\":\"Documento assinado digitalmente conforme MP nº 2.200-2/2001\"}"
                    + " [Origem:MovimentacaoNe.incluirMovimentacao]";

    /** Formato 3: texto livre, o caminho do inserirErro(LogErroDt). */
    public static final String TEXTO_LIVRE =
            "Não foi possível concluir a operação: o usuário não possui permissão de "
                    + "\"Assinar Documentos\" na serventia 3ª Vara de Execuções Penais da "
                    + "Comarca de Aparecida de Goiânia. Ação: gravação de sentença. "
                    + "Exceção: br.gov.go.tj.utils.MensagemException — código 162. "
                    + "Contate a Divisão de Suporte (ramal 4321) informando o horário. "
                    + " [Origem:ProcessoAcao.executarAcao]";

    /**
     * Caso-limite deliberado: aspas, barras invertidas, quebras de linha, tabs e
     * um apóstrofo — tudo o que quebra escaping mal feito em SQL montado por
     * concatenação. Vai junto no teste de integridade.
     */
    public static final String CARACTERES_DIFICEIS =
            "[Observação:aspas \" e ' e barra \\ e chaves {} e ponto-e-vírgula literal;"
                    + "Quebra:linha1\nlinha2\r\nlinha3;Tabulação:a\tb;"
                    + "Unicode:ção çãó ÀÉÎÕÜ ª º ° ± µ ¼ ½;Origem:TesteIntegridade]";

    /** Todos, para varredura em teste. */
    public static String[] todos() {
        return new String[]{
                PROPRIEDADES,
                PROPRIEDADES_ANTERIOR,
                JSON_COM_ORIGEM,
                TEXTO_LIVRE,
                CARACTERES_DIFICEIS
        };
    }
}
