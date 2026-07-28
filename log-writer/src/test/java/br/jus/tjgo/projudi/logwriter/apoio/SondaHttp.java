package br.jus.tjgo.projudi.logwriter.apoio;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sonda de baixo nível no endpoint {@code /ping} do ClickHouse.
 *
 * <h3>Por que existe</h3>
 *
 * <p>Quando o teste de integração não conecta, o driver JDBC devolve
 * {@code SQLException: Connection reset} com um rastro de 40 linhas que passa
 * por {@code ClickHouseConnectionImpl}, {@code AbstractClient} e
 * {@code ApacheHttpConnectionImpl} — e não diz a única coisa que importa:
 * <b>se há alguém escutando na porta, e se quem está lá é o ClickHouse</b>.</p>
 *
 * <p>Essa distinção separa causas completamente diferentes:</p>
 *
 * <ul>
 *   <li><b>Nada escutando</b> ({@code ConnectException: Connection refused}) —
 *       o container não está de pé, ou a porta não foi publicada.</li>
 *   <li><b>Conecta e é resetado</b> — alguém aceita o TCP e derruba em seguida.
 *       É a assinatura clássica do {@code docker-proxy}: ele aceita no host,
 *       tenta repassar ao container e não consegue. Container ainda executando
 *       os scripts de init, em laço de restart, ou com o servidor interno
 *       ouvindo só em {@code 127.0.0.1} da rede do container.</li>
 *   <li><b>Responde algo que não é {@code Ok.}</b> — a porta está ocupada por
 *       outro serviço.</li>
 * </ul>
 *
 * <p>Usa {@link Socket} cru de propósito: não depende do driver, então funciona
 * mesmo quando o problema é o próprio driver, e distingue o reset do refused,
 * que é o que o {@code SQLException} apaga.</p>
 */
public final class SondaHttp {

    /** {@code jdbc:ch://host:porta/base} ou {@code jdbc:clickhouse://…}. */
    private static final Pattern URL_JDBC =
            Pattern.compile("^jdbc:(?:ch|clickhouse):(?://)?([^:/?]+)(?::(\\d+))?.*$",
                    Pattern.CASE_INSENSITIVE);

    public enum Resultado {
        /** Ninguém escutando: connection refused já no handshake. */
        SEM_LISTENER,
        /**
         * O handshake TCP não completou dentro do tempo. Destino errado,
         * pacotes descartados, firewall — não é o servidor recusando.
         */
        INALCANCAVEL,
        /** Alguém aceitou o TCP e derrubou a conexão. */
        CONEXAO_RESETADA,
        /** Aceitou a conexão e não respondeu nada dentro do tempo. */
        SEM_RESPOSTA,
        /** Respondeu, mas não como o ClickHouse responde. */
        RESPOSTA_INESPERADA,
        /** Respondeu {@code Ok.} — o servidor está pronto. */
        PRONTO,
        /** Não foi possível sequer interpretar o destino. */
        DESTINO_INVALIDO
    }

    public static final class Diagnostico {
        public final Resultado resultado;
        public final String host;
        public final int porta;
        public final String detalhe;

        public Diagnostico(Resultado resultado, String host, int porta, String detalhe) {
            this.resultado = resultado;
            this.host = host;
            this.porta = porta;
            this.detalhe = detalhe;
        }

        public boolean pronto() {
            return resultado == Resultado.PRONTO;
        }

        @Override
        public String toString() {
            return resultado + " (" + host + ":" + porta + ") " + detalhe;
        }
    }

    private SondaHttp() {
    }

    /** Sonda o host e a porta extraídos de uma URL JDBC do ClickHouse. */
    public static Diagnostico sondarUrlJdbc(String urlJdbc, int timeoutMs) {
        Matcher m = URL_JDBC.matcher(String.valueOf(urlJdbc).trim());
        if (!m.matches()) {
            return new Diagnostico(Resultado.DESTINO_INVALIDO, "?", -1,
                    "não foi possível extrair host e porta de " + urlJdbc);
        }
        String host = m.group(1);
        int porta = (m.group(2) == null) ? 8123 : Integer.parseInt(m.group(2));
        return sondar(host, porta, timeoutMs);
    }

    public static Diagnostico sondar(String host, int porta, int timeoutMs) {
        Socket socket = new Socket();
        try {
            // Fase 1 — handshake. Separada da fase de leitura de propósito: um
            // timeout AQUI significa destino inalcançável, e um timeout DEPOIS
            // significa servidor que aceitou e não respondeu. Conflatar os dois
            // manda o diagnóstico para o lado errado.
            try {
                socket.connect(new InetSocketAddress(host, porta), timeoutMs);
            } catch (ConnectException e) {
                return new Diagnostico(Resultado.SEM_LISTENER, host, porta,
                        "connection refused: não há nada escutando");
            } catch (SocketTimeoutException e) {
                return new Diagnostico(Resultado.INALCANCAVEL, host, porta,
                        "o handshake TCP não completou em " + timeoutMs + " ms");
            }

            socket.setSoTimeout(timeoutMs);

            // Fase 2 — requisição e leitura.
            OutputStream saida = socket.getOutputStream();
            saida.write(("GET /ping HTTP/1.0\r\nHost: " + host + "\r\nConnection: close\r\n\r\n")
                    .getBytes("US-ASCII"));
            saida.flush();

            InputStream entrada = socket.getInputStream();
            byte[] buffer = new byte[512];
            int lidos = entrada.read(buffer);

            if (lidos < 0) {
                return new Diagnostico(Resultado.CONEXAO_RESETADA, host, porta,
                        "o servidor fechou a conexão sem responder nada");
            }
            String resposta = new String(buffer, 0, lidos, "US-ASCII");
            if (resposta.contains("Ok.")) {
                return new Diagnostico(Resultado.PRONTO, host, porta, "respondeu Ok. ao /ping");
            }
            String primeiraLinha = resposta.split("\\r?\\n", 2)[0];
            return new Diagnostico(Resultado.RESPOSTA_INESPERADA, host, porta,
                    "respondeu \"" + primeiraLinha + "\" — a porta pode estar ocupada por outro serviço");

        } catch (SocketTimeoutException e) {
            return new Diagnostico(Resultado.SEM_RESPOSTA, host, porta,
                    "conectou, mas não respondeu em " + timeoutMs + " ms");
        } catch (IOException e) {
            // "Connection reset" chega aqui como SocketException.
            return new Diagnostico(Resultado.CONEXAO_RESETADA, host, porta,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                // irrelevante
            }
        }
    }

    /**
     * Texto acionável para o diagnóstico — o que sai no lugar do rastro de
     * {@code Connection reset} do driver.
     */
    public static String explicar(Diagnostico d, String urlJdbc, String usuario) {
        StringBuilder sb = new StringBuilder();
        sb.append("Não foi possível falar com o ClickHouse em ").append(urlJdbc)
                .append(" (usuário ").append(usuario).append(").\n")
                .append("Sonda TCP em ").append(d.host).append(':').append(d.porta)
                .append(" -> ").append(d.resultado).append(" — ").append(d.detalhe).append("\n\n");

        switch (d.resultado) {
            case INALCANCAVEL:
                sb.append("O handshake TCP nem completou — ninguém recusou, ninguém atendeu.\n")
                        .append("  - host errado na URL                 -> confira clickhouse.url\n")
                        .append("  - compose e mvn em lados diferentes  -> em WSL, os dois precisam\n")
                        .append("                                          enxergar a mesma rede\n")
                        .append("  - firewall descartando os pacotes\n");
                break;
            case SEM_LISTENER:
                sb.append("Ninguém está escutando nessa porta. Causas prováveis:\n")
                        .append("  - o ambiente não está de pé          -> make up-lite\n")
                        .append("  - a porta publicada não é 8123       -> confira CLICKHOUSE_HTTP_PORT no .env\n")
                        .append("  - o compose subiu em outro host/WSL  -> docker compose ps\n");
                break;
            case SEM_RESPOSTA:
            case CONEXAO_RESETADA:
                sb.append(d.resultado == Resultado.SEM_RESPOSTA
                                ? "Alguém aceitou a conexão TCP e não respondeu nada.\n"
                                : "Alguém aceitou a conexão TCP e a derrubou.\n")
                        .append("Nos dois casos é a assinatura do docker-proxy aceitando no host e\n")
                        .append("não conseguindo repassar ao container. Verifique, nesta ordem:\n\n")
                        .append("  1. O container está mesmo rodando, e não reiniciando em laço?\n")
                        .append("     docker compose --env-file .env -f infra/docker-compose.yml ps\n")
                        .append("     (a coluna STATUS deve dizer Up, não Restarting)\n\n")
                        .append("  2. O init já terminou? Nos primeiros starts o entrypoint executa\n")
                        .append("     os 6 DDLs de infra/clickhouse/ddl/ e o servidor só passa a\n")
                        .append("     atender na porta publicada depois disso:\n")
                        .append("     docker compose --env-file .env -f infra/docker-compose.yml logs clickhouse\n")
                        .append("     (espere por \"Ready for connections\")\n\n")
                        .append("  3. O servidor morreu por falta de memória? O 10-projudi.xml limita\n")
                        .append("     o ClickHouse a 35% da RAM, mas num WSL com pouca memória isso\n")
                        .append("     ainda pode não bastar — procure por OOM nos logs acima.\n\n")
                        .append("  4. A porta está sendo usada por outro processo do host?\n")
                        .append("     ss -ltnp | grep 8123\n\n")
                        .append("ATENÇÃO: o healthcheck do compose usa clickhouse-client DENTRO do\n")
                        .append("container, pelo protocolo nativo. Ele pode reportar healthy sem que\n")
                        .append("a porta HTTP 8123 esteja utilizável a partir do host — que é o\n")
                        .append("caminho que este teste e o log-writer usam. `make up-lite` dizer\n")
                        .append("que está tudo certo não contradiz este diagnóstico.\n");
                break;
            case RESPOSTA_INESPERADA:
                sb.append("Há um serviço nessa porta, mas não é o ClickHouse.\n")
                        .append("  ss -ltnp | grep ").append(d.porta).append('\n');
                break;
            case DESTINO_INVALIDO:
                sb.append("A URL não pôde ser interpretada. Formato esperado:\n")
                        .append("  jdbc:ch://host:porta/base\n");
                break;
            default:
                sb.append("O servidor respondeu ao /ping, então a falha é depois do TCP:\n")
                        .append("  - usuário ou senha incorretos (veja CH_APP_USER/CH_APP_PASSWORD no .env)\n")
                        .append("  - a base projudi_logs não existe (os DDLs não rodaram)\n")
                        .append("  Teste direto:\n")
                        .append("  curl -s 'http://").append(d.host).append(':').append(d.porta)
                        .append("/?user=").append(usuario).append("&password=SENHA'")
                        .append(" --data-binary 'SELECT version()'\n");
                break;
        }
        return sb.toString();
    }
}
