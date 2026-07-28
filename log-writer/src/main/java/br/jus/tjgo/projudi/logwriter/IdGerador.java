package br.jus.tjgo.projudi.logwriter;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Gerador de {@code ID_LOG} no cliente, sem coordenação com banco nenhum.
 *
 * <h3>Formato (64 bits, estilo Snowflake)</h3>
 *
 * <pre>
 *   (millisDesdeEpoca &lt;&lt; 22) | (workerId &lt;&lt; 12) | sequencia
 *
 *    41 bits  millisDesdeEpoca  ~69 anos a partir de 2020-01-01Z
 *    10 bits  workerId          0..1023 JVMs distintas
 *    12 bits  sequencia         4096 IDs por milissegundo por JVM
 * </pre>
 *
 * <h3>Por que assim (ver docs/decisoes.md, decisão 20)</h3>
 *
 * <ul>
 *   <li><b>Zero round-trip.</b> Coerente com a decisão de não depender do Oracle
 *       no caminho de escrita — e é o único esquema compatível com gravação
 *       assíncrona, porque o ID precisa existir antes do flush, no instante em
 *       que a LogPs retorna para o chamador.</li>
 *   <li><b>Monotônico crescente.</b> Preserva o desempate do
 *       {@code ORDER BY (HORA, ID_USU, ID_LOG)} da {@code log_raw} e mantém a
 *       correlação temporal do identificador.</li>
 *   <li><b>Cabe em {@code UInt64} e em {@code NUMBER(24)}</b> nos dois lados.</li>
 *   <li><b>Faixa disjunta do histórico.</b> A {@code LOG_ID_LOG_SEQ} de produção
 *       está na casa de 1,05×10⁸; um ID gerado aqui fica na casa de 8,7×10¹⁷.
 *       Nenhuma colisão possível na migração, e a origem de cada registro é
 *       legível pela ordem de grandeza do próprio ID.</li>
 *   <li><b>Habilita idempotência</b> do reenvio de lote e a verificação de
 *       completude do benchmark por {@code count(DISTINCT ID_LOG)}.</li>
 * </ul>
 *
 * <p>Thread-safe. A instância é compartilhada por todas as threads de request.</p>
 */
public final class IdGerador {

    private static final Logger LOG = Logger.getLogger(IdGerador.class.getName());

    /** 2020-01-01T00:00:00Z em milissegundos. */
    public static final long EPOCA_MS = 1577836800000L;

    public static final int BITS_SEQUENCIA = 12;
    public static final int BITS_WORKER = 10;

    public static final long MAX_WORKER_ID = (1L << BITS_WORKER) - 1L;   // 1023
    public static final long MASCARA_SEQUENCIA = (1L << BITS_SEQUENCIA) - 1L; // 4095

    private static final int DESLOCAMENTO_WORKER = BITS_SEQUENCIA;
    private static final int DESLOCAMENTO_TEMPO = BITS_SEQUENCIA + BITS_WORKER;

    /** Relógio isolado para que o teste exercite viradas de milissegundo. */
    public interface Relogio {
        long agoraMs();
    }

    private static final Relogio RELOGIO_SISTEMA = new Relogio() {
        @Override
        public long agoraMs() {
            return System.currentTimeMillis();
        }
    };

    private final long workerId;
    private final Relogio relogio;

    private long ultimoMs = -1L;
    private long sequencia = 0L;

    public IdGerador(long workerId) {
        this(workerId, RELOGIO_SISTEMA);
    }

    public IdGerador(long workerId, Relogio relogio) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException(
                    "workerId precisa estar entre 0 e " + MAX_WORKER_ID + "; veio " + workerId);
        }
        if (relogio == null) {
            throw new IllegalArgumentException("relogio não pode ser nulo");
        }
        this.workerId = workerId;
        this.relogio = relogio;
    }

    public long getWorkerId() {
        return workerId;
    }

    /**
     * Próximo identificador. Bloqueia (busy-wait de no máximo 1 ms) quando os
     * 4096 slots do milissegundo corrente se esgotam.
     */
    public synchronized long proximo() {
        long agora = relogio.agoraMs();

        if (agora < ultimoMs) {
            // Relógio andou para trás (ajuste de NTP, ou VM migrada). Esperar é
            // preferível a emitir IDs que colidam com os já entregues.
            long atraso = ultimoMs - agora;
            LOG.log(Level.WARNING, "Relógio retrocedeu {0} ms; aguardando para não repetir ID_LOG.", atraso);
            agora = esperarAte(ultimoMs);
        }

        if (agora == ultimoMs) {
            sequencia = (sequencia + 1L) & MASCARA_SEQUENCIA;
            if (sequencia == 0L) {
                // Estouraram os 4096 IDs deste milissegundo: comportamento
                // definido é esperar o próximo, nunca reutilizar.
                agora = esperarAte(ultimoMs + 1L);
            }
        } else {
            sequencia = 0L;
        }

        ultimoMs = agora;

        long deltaEpoca = agora - EPOCA_MS;
        if (deltaEpoca < 0) {
            throw new IllegalStateException(
                    "Relógio do sistema anterior à época do gerador (2020-01-01Z): " + agora);
        }
        return (deltaEpoca << DESLOCAMENTO_TEMPO) | (workerId << DESLOCAMENTO_WORKER) | sequencia;
    }

    private long esperarAte(long alvoMs) {
        long agora = relogio.agoraMs();
        while (agora < alvoMs) {
            agora = relogio.agoraMs();
        }
        return agora;
    }

    // ---- decodificação, usada pelos testes e pela conferência do benchmark ---

    public static long instanteDe(long idLog) {
        return (idLog >>> DESLOCAMENTO_TEMPO) + EPOCA_MS;
    }

    public static long workerDe(long idLog) {
        return (idLog >>> DESLOCAMENTO_WORKER) & MAX_WORKER_ID;
    }

    public static long sequenciaDe(long idLog) {
        return idLog & MASCARA_SEQUENCIA;
    }

    /**
     * Resolve o {@code workerId} da configuração; se ausente, deriva de
     * hostname + PID e <b>avisa no log</b>.
     *
     * <p>O aviso não é decoração: o Projudi roda em várias JVMs contra o mesmo
     * destino, e um workerId derivado é um hash — a chance de duas instâncias
     * caírem no mesmo valor não é zero (com 1024 slots e 4 instâncias, ~0,6%).
     * Em produção o valor deve ser atribuído explicitamente por instância.</p>
     */
    public static long resolverWorkerId(Long configurado) {
        if (configurado != null) {
            if (configurado < 0 || configurado > MAX_WORKER_ID) {
                throw new IllegalArgumentException(
                        "workerId configurado fora da faixa 0.." + MAX_WORKER_ID + ": " + configurado);
            }
            return configurado.longValue();
        }

        String identidade = identidadeDoProcesso();
        long derivado = Math.abs((long) identidade.hashCode()) & MAX_WORKER_ID;
        LOG.log(Level.WARNING,
                "workerId não configurado; derivado de \"{0}\" -> {1}. "
                        + "Defina projudi.logwriter.workerId (ou PROJUDI_LOGWRITER_WORKER_ID) "
                        + "explicitamente por instância: IDs de instâncias distintas podem colidir.",
                new Object[]{identidade, derivado});
        return derivado;
    }

    static String identidadeDoProcesso() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "host-desconhecido";
        }
        String pid;
        try {
            // Java 8 não tem ProcessHandle.current().pid().
            pid = ManagementFactory.getRuntimeMXBean().getName();
        } catch (Exception e) {
            pid = "pid-desconhecido";
        }
        return host + "/" + pid;
    }
}
