package br.jus.tjgo.projudi.logwriter.apoio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Testa a sonda contra servidores de mentira que <b>reproduzem</b> cada sintoma
 * — inclusive o {@code Connection reset} que apareceu no WSL — sem precisar de
 * ClickHouse, Docker ou rede externa.
 */
class SondaHttpTest {

    private static final int TIMEOUT = 2000;

    /** Servidor descartável que atende uma conexão e faz o que o teste mandar. */
    private static final class ServidorFalso implements AutoCloseable {
        private final ServerSocket servidor;
        private final Thread thread;

        ServidorFalso(final Comportamento comportamento) throws IOException {
            this.servidor = new ServerSocket(0);
            final CountDownLatch pronto = new CountDownLatch(1);
            this.thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    pronto.countDown();
                    try {
                        Socket cliente = servidor.accept();
                        comportamento.atender(cliente);
                    } catch (IOException e) {
                        // servidor fechado pelo teste
                    }
                }
            }, "servidor-falso");
            this.thread.setDaemon(true);
            this.thread.start();
            try {
                pronto.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        int porta() {
            return servidor.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            servidor.close();
        }
    }

    private interface Comportamento {
        void atender(Socket cliente) throws IOException;
    }

    @Test
    @Timeout(30)
    @DisplayName("porta sem ninguém escutando é reconhecida como SEM_LISTENER")
    void portaFechada() throws Exception {
        int porta;
        ServerSocket temporario = new ServerSocket(0);
        porta = temporario.getLocalPort();
        temporario.close(); // libera a porta antes de sondar

        SondaHttp.Diagnostico d = SondaHttp.sondar("127.0.0.1", porta, TIMEOUT);

        // SEM_LISTENER (o normal: RST no handshake) ou INALCANCAVEL — o Windows
        // às vezes descarta em silêncio o SYN para uma porta recém-liberada, em
        // vez de recusar. O que não pode acontecer é ser classificado como
        // problema DO SERVIDOR, que é o outro ramo do diagnóstico.
        assertTrue(d.resultado == SondaHttp.Resultado.SEM_LISTENER
                        || d.resultado == SondaHttp.Resultado.INALCANCAVEL,
                "esperado SEM_LISTENER ou INALCANCAVEL, veio " + d);
        assertFalse(d.pronto());
    }

    @Test
    @Timeout(30)
    @DisplayName("porta livre produz diagnóstico de cliente, com o comando para subir o ambiente")
    void mensagemDePortaFechada() {
        SondaHttp.Diagnostico d = new SondaHttp.Diagnostico(
                SondaHttp.Resultado.SEM_LISTENER, "localhost", 8123, "connection refused");
        String texto = SondaHttp.explicar(d, "jdbc:ch://localhost:8123/projudi_logs", "projudi_app");
        assertTrue(texto.contains("make up-lite"), texto);
        assertTrue(texto.contains("CLICKHOUSE_HTTP_PORT"), texto);
    }

    @Test
    @Timeout(30)
    @DisplayName("aceitar e resetar — o sintoma exato do WSL — vira CONEXAO_RESETADA")
    void aceitaEReseta() throws Exception {
        // SO_LINGER com timeout 0 faz o close() emitir RST em vez de FIN, que é
        // precisamente o que o docker-proxy faz quando não consegue repassar a
        // conexão ao container.
        ServidorFalso servidor = new ServidorFalso(new Comportamento() {
            @Override
            public void atender(Socket cliente) throws IOException {
                cliente.setSoLinger(true, 0);
                cliente.close();
            }
        });
        try {
            SondaHttp.Diagnostico d = SondaHttp.sondar("127.0.0.1", servidor.porta(), TIMEOUT);

            assertEquals(SondaHttp.Resultado.CONEXAO_RESETADA, d.resultado, d.toString());
            String texto = SondaHttp.explicar(d,
                    "jdbc:ch://127.0.0.1:" + servidor.porta() + "/x", "projudi_app");
            assertTrue(texto.contains("docker-proxy"), texto);
            assertTrue(texto.contains("Restarting"), texto);
            assertTrue(texto.contains("healthcheck"), texto);
        } finally {
            servidor.close();
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("um ClickHouse saudável responde Ok. e vira PRONTO")
    void respondeOk() throws Exception {
        ServidorFalso servidor = new ServidorFalso(new Comportamento() {
            @Override
            public void atender(Socket cliente) throws IOException {
                OutputStream saida = cliente.getOutputStream();
                saida.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n"
                        + "Content-Length: 4\r\n\r\nOk.\n").getBytes("US-ASCII"));
                saida.flush();
                cliente.close();
            }
        });
        try {
            SondaHttp.Diagnostico d = SondaHttp.sondar("127.0.0.1", servidor.porta(), TIMEOUT);

            assertEquals(SondaHttp.Resultado.PRONTO, d.resultado, d.toString());
            assertTrue(d.pronto());
            // Com o TCP saudável, o diagnóstico aponta para credencial e base.
            String texto = SondaHttp.explicar(d,
                    "jdbc:ch://127.0.0.1:" + servidor.porta() + "/x", "projudi_app");
            assertTrue(texto.contains("projudi_logs"), texto);
        } finally {
            servidor.close();
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("cabeçalho e corpo em segmentos separados ainda dão PRONTO")
    void respostaFatiadaEmDoisSegmentos() throws Exception {
        // E assim que o ClickHouse real responde: cabecalhos primeiro, corpo
        // depois, em chunk separado. Uma sonda que faz um read() so enxerga
        // "HTTP/1.1 200 OK" e conclui, errado, que ha outro servico na porta.
        ServidorFalso servidor = new ServidorFalso(new Comportamento() {
            @Override
            public void atender(Socket cliente) throws IOException {
                OutputStream saida = cliente.getOutputStream();
                saida.write(("HTTP/1.1 200 OK\r\nConnection: Close\r\n"
                        + "Content-Type: text/html; charset=UTF-8\r\n"
                        + "Transfer-Encoding: chunked\r\n\r\n")
                        .getBytes("US-ASCII"));
                saida.flush();
                try {
                    Thread.sleep(150L); // forca segmentos distintos
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                saida.write(("4\r\nOk.\n\r\n0\r\n\r\n")
                        .getBytes("US-ASCII"));
                saida.flush();
                cliente.close();
            }
        });
        try {
            SondaHttp.Diagnostico d = SondaHttp.sondar("127.0.0.1", servidor.porta(), TIMEOUT);
            assertEquals(SondaHttp.Resultado.PRONTO, d.resultado,
                    "resposta fatiada foi classificada errado: " + d);
        } finally {
            servidor.close();
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("outro serviço na porta vira RESPOSTA_INESPERADA")
    void outroServicoNaPorta() throws Exception {
        ServidorFalso servidor = new ServidorFalso(new Comportamento() {
            @Override
            public void atender(Socket cliente) throws IOException {
                OutputStream saida = cliente.getOutputStream();
                saida.write("SSH-2.0-OpenSSH_9.6\r\n".getBytes("US-ASCII"));
                saida.flush();
                cliente.close();
            }
        });
        try {
            SondaHttp.Diagnostico d = SondaHttp.sondar("127.0.0.1", servidor.porta(), TIMEOUT);

            assertEquals(SondaHttp.Resultado.RESPOSTA_INESPERADA, d.resultado, d.toString());
            assertTrue(d.detalhe.contains("SSH-2.0-OpenSSH_9.6"), d.detalhe);
        } finally {
            servidor.close();
        }
    }

    @Test
    @DisplayName("host e porta são extraídos da URL JDBC, com 8123 como padrão")
    void extracaoDaUrl() {
        SondaHttp.Diagnostico d = SondaHttp.sondarUrlJdbc("jdbc:ch://naoexiste.invalido/x", 500);
        assertEquals("naoexiste.invalido", d.host);
        assertEquals(8123, d.porta);

        d = SondaHttp.sondarUrlJdbc("jdbc:clickhouse://naoexiste.invalido:9999/x", 500);
        assertEquals("naoexiste.invalido", d.host);
        assertEquals(9999, d.porta);

        d = SondaHttp.sondarUrlJdbc("jdbc:postgresql://localhost/x", 500);
        assertEquals(SondaHttp.Resultado.DESTINO_INVALIDO, d.resultado);
    }
}
