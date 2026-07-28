package br.jus.tjgo.projudi.logwriter;

import java.io.Serializable;
import java.util.Date;

/**
 * Uma linha da PROJUDI.LOG, imutável, pronta para gravação.
 *
 * <p><b>Espelho fiel das 13 colunas de {@code projudi_logs.log_raw}.</b> Nenhum
 * parsing é feito: {@code VALOR_ATUAL} e {@code VALOR_NOVO} são transportados
 * como {@link String} exatamente como a LogDt os montou. Essa é a premissa da
 * Solução 1 — trocar o destino sem trocar o formato.</p>
 *
 * <h3>Por que o Builder recebe String</h3>
 *
 * <p>A {@code LogDt} do Projudi guarda <i>tudo</i> como String, inclusive os
 * identificadores numéricos, e usa {@code ""} para "ausente". O Builder aceita
 * essas Strings diretamente e faz a conversão tolerante aqui dentro, para que o
 * adaptador dentro da LogPs seja uma sequência de chamadas óbvia, sem
 * {@code Long.parseLong} espalhado por código legado.</p>
 *
 * <h3>Regras copiadas da LogPs.inserir(LogDt)</h3>
 *
 * <ul>
 *   <li>{@code TABELA} sofre {@code trim()} e, se passar de 60 caracteres, é
 *       truncada em 59 — exatamente a condição do código atual
 *       ({@code if (len > 60) substring(0, 59)}), inclusive a assimetria de
 *       deixar passar o comprimento 60 inteiro.</li>
 *   <li>{@code HORA} é o instante da gravação; {@code DATA}, quando não vem
 *       preenchida, é derivada da mesma instância de {@code HORA} em vez de um
 *       segundo {@code new Date()}. O código atual chama {@code new Date()} duas
 *       vezes e pode gravar DATA e HORA em dias diferentes na virada da
 *       meia-noite; aqui isso não acontece.</li>
 *   <li>Colunas que a LogPs omite do INSERT quando vazias chegam ao ClickHouse
 *       como {@code ''} — as colunas correspondentes da {@code log_raw} são
 *       {@code String} não-Nullable com essa mesma semântica (ver o comentário
 *       do DDL em infra/clickhouse/ddl/02_log_raw.sql).</li>
 * </ul>
 *
 * <p><b>Diferença conhecida:</b> a LogPs passa {@code TABELA} e
 * {@code IP_COMPUTADOR} por {@code Funcoes.removeEspacosExcesso} e
 * {@code Funcoes.removerCaracteresControleEspeciais} (efeito de
 * {@code PreparedStatementTJGO.adicionarString}), enquanto os CLOBs vão crus
 * ({@code adicionarClob} não sanitiza). Esta biblioteca não replica a
 * sanitização — ela vive em {@code br.gov.go.tj.utils.Funcoes}, que é código do
 * Projudi. Se a fidelidade dessas duas colunas curtas importar, a LogPs deve
 * aplicar {@code Funcoes} antes de montar o registro. Os CLOBs, que são o dado
 * que interessa, continuam byte a byte iguais nos dois caminhos.</p>
 */
public final class LogRegistro implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Limite da coluna TABELA na PROJUDI.LOG de produção: VARCHAR2(60 CHAR). */
    private static final int LIMITE_TABELA = 60;
    private static final int CORTE_TABELA = 59;

    private final long idLog;
    private final long idLogTipo;
    private final long logTipoCodigo;
    private final long idUsu;
    private final String ipComputador;
    private final Date data;
    private final Date hora;
    private final String tabela;
    private final String valorAtual;
    private final String valorNovo;
    private final Long codigoTemp;
    private final Long idTabela;
    private final String hash;
    private final Integer qtdErrosDia;

    private LogRegistro(Builder b) {
        this.idLog = b.idLog;
        this.idLogTipo = b.idLogTipo;
        this.logTipoCodigo = b.logTipoCodigo;
        this.idUsu = b.idUsu;
        this.ipComputador = b.ipComputador;
        this.tabela = b.tabela;
        this.valorAtual = b.valorAtual;
        this.valorNovo = b.valorNovo;
        this.codigoTemp = b.codigoTemp;
        this.idTabela = b.idTabela;
        this.hash = b.hash;
        this.qtdErrosDia = b.qtdErrosDia;

        Date instante = (b.hora != null) ? b.hora : new Date();
        this.hora = new Date(instante.getTime());
        this.data = (b.data != null) ? new Date(b.data.getTime()) : new Date(instante.getTime());
    }

    public static Builder novo() {
        return new Builder();
    }

    public long getIdLog() {
        return idLog;
    }

    public long getIdLogTipo() {
        return idLogTipo;
    }

    /** Código de negócio do tipo de log; {@code 0} quando não informado. */
    public long getLogTipoCodigo() {
        return logTipoCodigo;
    }

    public long getIdUsu() {
        return idUsu;
    }

    public String getIpComputador() {
        return ipComputador;
    }

    public Date getData() {
        return new Date(data.getTime());
    }

    public Date getHora() {
        return new Date(hora.getTime());
    }

    public String getTabela() {
        return tabela;
    }

    public String getValorAtual() {
        return valorAtual;
    }

    public String getValorNovo() {
        return valorNovo;
    }

    public Long getCodigoTemp() {
        return codigoTemp;
    }

    public Long getIdTabela() {
        return idTabela;
    }

    public String getHash() {
        return hash;
    }

    public Integer getQtdErrosDia() {
        return qtdErrosDia;
    }

    /** Cópia com o ID_LOG preenchido — o registro em si continua imutável. */
    public LogRegistro comId(long novoIdLog) {
        Builder b = paraBuilder();
        b.idLog = novoIdLog;
        return new LogRegistro(b);
    }

    /** Cópia com o ID_LOG_TIPO resolvido. */
    public LogRegistro comIdLogTipo(long novoIdLogTipo) {
        Builder b = paraBuilder();
        b.idLogTipo = novoIdLogTipo;
        return new LogRegistro(b);
    }

    private Builder paraBuilder() {
        Builder b = new Builder();
        b.idLog = this.idLog;
        b.idLogTipo = this.idLogTipo;
        b.logTipoCodigo = this.logTipoCodigo;
        b.idUsu = this.idUsu;
        b.ipComputador = this.ipComputador;
        b.data = this.data;
        b.hora = this.hora;
        b.tabela = this.tabela;
        b.valorAtual = this.valorAtual;
        b.valorNovo = this.valorNovo;
        b.codigoTemp = this.codigoTemp;
        b.idTabela = this.idTabela;
        b.hash = this.hash;
        b.qtdErrosDia = this.qtdErrosDia;
        return b;
    }

    @Override
    public String toString() {
        return "LogRegistro[ID_LOG=" + idLog
                + ", ID_LOG_TIPO=" + idLogTipo
                + ", ID_USU=" + idUsu
                + ", TABELA=" + tabela
                + ", ID_TABELA=" + idTabela
                + ", HORA=" + hora
                + ", |VALOR_ATUAL|=" + valorAtual.length()
                + ", |VALOR_NOVO|=" + valorNovo.length() + "]";
    }

    // -------------------------------------------------------------------------

    public static final class Builder {

        private long idLog;
        private long idLogTipo;
        private long logTipoCodigo;
        private long idUsu;
        private String ipComputador = "";
        private Date data;
        private Date hora;
        private String tabela = "";
        private String valorAtual = "";
        private String valorNovo = "";
        private Long codigoTemp;
        private Long idTabela;
        private String hash;
        private Integer qtdErrosDia;

        public Builder idLog(long valor) {
            this.idLog = valor;
            return this;
        }

        public Builder idLog(String valor) {
            this.idLog = paraLong(valor, 0L);
            return this;
        }

        public Builder idLogTipo(long valor) {
            this.idLogTipo = valor;
            return this;
        }

        public Builder idLogTipo(String valor) {
            this.idLogTipo = paraLong(valor, 0L);
            return this;
        }

        public Builder logTipoCodigo(long valor) {
            this.logTipoCodigo = valor;
            return this;
        }

        public Builder logTipoCodigo(String valor) {
            this.logTipoCodigo = paraLong(valor, 0L);
            return this;
        }

        public Builder idUsuario(long valor) {
            this.idUsu = valor;
            return this;
        }

        public Builder idUsuario(String valor) {
            this.idUsu = paraLong(valor, 0L);
            return this;
        }

        public Builder ipComputador(String valor) {
            this.ipComputador = (valor == null) ? "" : valor;
            return this;
        }

        public Builder data(Date valor) {
            this.data = (valor == null) ? null : new Date(valor.getTime());
            return this;
        }

        public Builder hora(Date valor) {
            this.hora = (valor == null) ? null : new Date(valor.getTime());
            return this;
        }

        /**
         * Aplica a mesma regra da LogPs: {@code trim()} e corte em 59 quando o
         * comprimento passa de 60.
         */
        public Builder tabela(String valor) {
            if (valor == null) {
                this.tabela = "";
            } else {
                String limpo = valor.trim();
                this.tabela = (limpo.length() > LIMITE_TABELA)
                        ? limpo.substring(0, CORTE_TABELA)
                        : limpo;
            }
            return this;
        }

        public Builder valorAtual(String valor) {
            this.valorAtual = (valor == null) ? "" : valor;
            return this;
        }

        public Builder valorNovo(String valor) {
            this.valorNovo = (valor == null) ? "" : valor;
            return this;
        }

        public Builder codigoTemp(Long valor) {
            this.codigoTemp = valor;
            return this;
        }

        public Builder codigoTemp(String valor) {
            this.codigoTemp = paraLongOuNulo(valor);
            return this;
        }

        public Builder idTabela(Long valor) {
            this.idTabela = valor;
            return this;
        }

        public Builder idTabela(String valor) {
            this.idTabela = paraLongOuNulo(valor);
            return this;
        }

        /** Coluna {@code FixedString(32)}: só aceita hash MD5 hexadecimal. */
        public Builder hash(String valor) {
            this.hash = (valor == null || valor.trim().isEmpty()) ? null : valor.trim();
            return this;
        }

        public Builder qtdErrosDia(Integer valor) {
            this.qtdErrosDia = valor;
            return this;
        }

        public Builder qtdErrosDia(long valor) {
            this.qtdErrosDia = (valor == 0L) ? null : Integer.valueOf((int) valor);
            return this;
        }

        public LogRegistro construir() {
            if (hash != null && hash.length() != 32) {
                throw new IllegalArgumentException(
                        "HASH precisa ter exatamente 32 caracteres (FixedString(32) na log_raw); veio "
                                + hash.length());
            }
            return new LogRegistro(this);
        }

        private static long paraLong(String valor, long padrao) {
            Long convertido = paraLongOuNulo(valor);
            return (convertido == null) ? padrao : convertido.longValue();
        }

        private static Long paraLongOuNulo(String valor) {
            if (valor == null) {
                return null;
            }
            String limpo = valor.trim();
            if (limpo.isEmpty() || "null".equalsIgnoreCase(limpo)) {
                return null;
            }
            try {
                return Long.valueOf(limpo);
            } catch (NumberFormatException e) {
                // A LogDt aceita qualquer String nesses campos. Um valor não
                // numérico é dado ruim vindo da origem, não motivo para
                // derrubar a gravação do log.
                return null;
            }
        }
    }
}
