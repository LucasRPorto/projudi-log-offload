package br.jus.tjgo.projudi.logwriter.bench;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import br.jus.tjgo.projudi.logwriter.ConexaoSupplier;
import br.jus.tjgo.projudi.logwriter.IdGerador;
import br.jus.tjgo.projudi.logwriter.LogRegistro;
import br.jus.tjgo.projudi.logwriter.LogSink;
import br.jus.tjgo.projudi.logwriter.LogWriterException;
import br.jus.tjgo.projudi.logwriter.apoio.PayloadsReais;
import br.jus.tjgo.projudi.logwriter.sink.ClickHouseLogSink;
import br.jus.tjgo.projudi.logwriter.sink.OracleLogSink;

/**
 * Harness de medição: escreve N registros no <b>Oracle</b> e no
 * <b>ClickHouse</b> do compose, no mesmo host e na mesma janela de tempo, e
 * cronometra os dois.
 *
 * <h3>Metodologia (docs/ambientes.md, seção 3)</h3>
 *
 * <ul>
 *   <li><b>Não depende do Projudi.</b> Nem de Eclipse, nem de Tomcat, nem da
 *       base do TJ-GO. É um {@code main} que fala JDBC com os dois containers.</li>
 *   <li><b>Não depende de estado local.</b> Os IDs são gerados pelo
 *       {@code IdGerador}, então rodadas sucessivas nunca colidem, e o resultado
 *       não muda conforme o que já existe nas tabelas. Com
 *       {@code bench.limpar=true} as duas tabelas são esvaziadas antes de
 *       começar, deixando as duas rodadas partindo do mesmo ponto.</li>
 *   <li><b>Os dois destinos recebem exatamente o mesmo payload</b>, gerado uma
 *       vez antes de qualquer cronômetro — nenhum tempo de construção de objeto
 *       entra na medição de nenhum dos lados.</li>
 *   <li><b>Warmup separado e explícito</b>, para não medir JIT frio, abertura de
 *       conexão nem primeira alocação de buffer.</li>
 *   <li><b>Repetições</b>, com mediana reportada além dos valores individuais.
 *       Uma rodada única não é medição, é anedota.</li>
 * </ul>
 *
 * <p><b>O número final tem que sair de um único ambiente de referência.</b>
 * Rodar metade aqui e metade em outra máquina produz números que não se
 * combinam. O cabeçalho da saída registra a máquina justamente para que isso
 * fique verificável no relatório.</p>
 *
 * <h3>Como rodar</h3>
 *
 * <pre>
 * # com o compose de pé (make up)
 * mvn -q test-compile exec:java@bench -Dbench.n=20000 -Dbench.lotes=1,100,500,2000
 *
 * # só ClickHouse, quando o ambiente está em make up-lite
 * mvn -q test-compile exec:java@bench -Dbench.n=20000 -Dbench.oracle=false
 * </pre>
 */
public final class BenchmarkHarness {

    private static final String LINHA =
            "--------------------------------------------------------------------------------";

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            imprimirAjuda();
            return;
        }

        Parametros p = new Parametros(args);

        // Espelha tudo num arquivo UTF-8: o console do Windows roda em Cp1252 e
        // exibe a acentuacao errada mesmo com o conteudo correto na memoria.
        String saida = p.texto("bench.saida", null, "");
        java.io.PrintStream consoleOriginal = null;
        if (!saida.trim().isEmpty()) {
            consoleOriginal = SaidaDupla.instalar(saida.trim());
        }

        int n = p.inteiro("bench.n", 10_000);
        int warmup = p.inteiro("bench.warmup", 1_000);
        int repeticoes = p.inteiro("bench.repeticoes", 3);
        List<Integer> lotes = p.listaDeInteiros("bench.lotes", "1,100,500,2000");
        boolean limpar = p.booleano("bench.limpar", true);
        boolean usarOracle = p.booleano("bench.oracle", true);
        boolean usarClickHouse = p.booleano("bench.clickhouse", true);
        // Modo seco: grava em memória, sem banco nenhum. NÃO é medição — serve
        // para conferir a invocação, os parâmetros e o formato da saída antes de
        // gastar tempo do ambiente de referência, e para exercitar este código
        // numa máquina sem Docker.
        boolean seco = p.booleano("bench.seco", false);

        String chUrl = p.texto("bench.ch.url", null, "jdbc:ch://localhost:8123/projudi_logs");
        String chUsuario = p.texto("bench.ch.usuario", "CH_APP_USER", "projudi_app");
        String chSenha = p.texto("bench.ch.senha", "CH_APP_PASSWORD", "projudi_app_dev");

        String oraUrl = p.texto("bench.oracle.url", null, "jdbc:oracle:thin:@//localhost:1521/FREEPDB1");
        String oraUsuario = p.texto("bench.oracle.usuario", null, "PROJUDI");
        String oraSenha = p.texto("bench.oracle.senha", "ORACLE_PROJUDI_PASSWORD", "projudi_dev");

        imprimirCabecalho(p, n, warmup, repeticoes, lotes);

        ConexaoSupplier conexoesCh =
                new ConexaoSupplier.DoDriverManager(chUrl, chUsuario, chSenha);
        ConexaoSupplier conexoesOra =
                new ConexaoSupplier.DoDriverManager(oraUrl, oraUsuario, oraSenha);

        boolean chDisponivel = !seco && usarClickHouse && verificar("ClickHouse", conexoesCh, "SELECT 1");
        boolean oraDisponivel = !seco && usarOracle && verificar("Oracle", conexoesOra, "SELECT 1 FROM DUAL");

        if (seco) {
            System.out.println("  MODO SECO: nenhum banco é consultado.");
        }

        if (!seco && !chDisponivel && !oraDisponivel) {
            System.out.println();
            System.out.println("Nenhum destino disponível. Suba o ambiente com `make up` "
                    + "(ou `make up-lite` para só o ClickHouse) e rode de novo.");
            System.exit(2);
        }
        if (!seco && !oraDisponivel && usarOracle) {
            System.out.println();
            System.out.println("AVISO: sem Oracle não existe grupo de controle. O número do");
            System.out.println("       ClickHouse sozinho NÃO serve para o relatório — ver");
            System.out.println("       docs/ambientes.md, seção 3.");
        }

        if (limpar && !seco) {
            if (chDisponivel) {
                executar(conexoesCh, "TRUNCATE TABLE projudi_logs.log_raw");
            }
            if (oraDisponivel) {
                executar(conexoesOra, "TRUNCATE TABLE PROJUDI.LOG");
            }
            System.out.println();
            System.out.println("Tabelas esvaziadas antes da medição (bench.limpar=true).");
        }

        // Payload construído UMA vez, fora de qualquer cronômetro.
        System.out.println();
        System.out.println("Gerando " + (n + warmup) + " registros de carga (payloads reais)...");
        List<LogRegistro> carga = gerarCarga(n + warmup);

        List<Resultado> resultados = new ArrayList<Resultado>();

        for (Integer lote : lotes) {
            if (seco) {
                resultados.add(medir("Memória(seco)", lote.intValue(), n, warmup, repeticoes, carga,
                        new FabricaDeSink() {
                            @Override
                            public LogSink criar() {
                                return new br.jus.tjgo.projudi.logwriter.sink.MemoriaLogSink();
                            }
                        }));
                continue;
            }
            if (chDisponivel) {
                resultados.add(medir("ClickHouse", lote.intValue(), n, warmup, repeticoes, carga,
                        new FabricaDeSink() {
                            @Override
                            public LogSink criar() {
                                return new ClickHouseLogSink(conexoesCh);
                            }
                        }));
            }
            if (oraDisponivel) {
                resultados.add(medir("Oracle", lote.intValue(), n, warmup, repeticoes, carga,
                        new FabricaDeSink() {
                            @Override
                            public LogSink criar() {
                                return new OracleLogSink(conexoesOra);
                            }
                        }));
            }
        }

        imprimirTabela(resultados, n);
        if (!seco) {
            conferirCompletude(chDisponivel ? conexoesCh : null, oraDisponivel ? conexoesOra : null);
        }
        imprimirRodape();
        if (seco) {
            System.out.println();
            System.out.println("MODO SECO: os tempos acima medem apenas a montagem dos lotes em");
            System.out.println("memória. NÃO use no relatório. Rode com o compose de pé e sem");
            System.out.println("bench.seco para obter a comparação Oracle x ClickHouse.");
        }

        if (consoleOriginal != null) {
            System.out.flush();
            System.out.close();
            System.setOut(consoleOriginal);
            System.out.println();
            System.out.println("Relatório gravado em UTF-8: " + saida.trim());
        }
    }

    // -------------------------------------------------------------------------

    private interface FabricaDeSink {
        LogSink criar();
    }

    private static final class Resultado {
        final String destino;
        final int lote;
        final List<Long> nanosPorRepeticao = new ArrayList<Long>();

        Resultado(String destino, int lote) {
            this.destino = destino;
            this.lote = lote;
        }

        long medianaNanos() {
            List<Long> ordenado = new ArrayList<Long>(nanosPorRepeticao);
            Collections.sort(ordenado);
            int meio = ordenado.size() / 2;
            if (ordenado.size() % 2 == 1) {
                return ordenado.get(meio).longValue();
            }
            return (ordenado.get(meio - 1).longValue() + ordenado.get(meio).longValue()) / 2L;
        }

        long menorNanos() {
            return Collections.min(nanosPorRepeticao).longValue();
        }

        long maiorNanos() {
            return Collections.max(nanosPorRepeticao).longValue();
        }
    }

    private static Resultado medir(String destino, int lote, int n, int warmup, int repeticoes,
                                   List<LogRegistro> carga, FabricaDeSink fabrica) throws Exception {
        System.out.println();
        System.out.println("Medindo " + destino + " com lote=" + lote + "...");

        Resultado resultado = new Resultado(destino, lote);

        for (int r = 1; r <= repeticoes; r++) {
            LogSink sink = fabrica.criar();
            try {
                // Warmup: JIT, conexão, buffers. Fora do cronômetro.
                gravar(sink, carga.subList(0, warmup), lote);

                long inicio = System.nanoTime();
                gravar(sink, carga.subList(warmup, warmup + n), lote);
                long decorrido = System.nanoTime() - inicio;

                resultado.nanosPorRepeticao.add(Long.valueOf(decorrido));
                System.out.printf("  repetição %d/%d: %,.0f ms  (%,.0f reg/s)%n",
                        Integer.valueOf(r), Integer.valueOf(repeticoes),
                        Double.valueOf(decorrido / 1e6),
                        Double.valueOf(n / (decorrido / 1e9)));
            } finally {
                sink.close();
            }
        }
        return resultado;
    }

    private static void gravar(LogSink sink, List<LogRegistro> registros, int lote)
            throws LogWriterException {
        // Cada rodada precisa de IDs novos: a PROJUDI.LOG tem PK em ID_LOG e um
        // ID repetido faria a segunda repetição falhar por constraint em vez de
        // medir. É o mesmo motivo pelo qual o benchmark não depende de estado
        // local — cada execução gera a própria faixa de IDs.
        for (int i = 0; i < registros.size(); i += lote) {
            int fim = Math.min(i + lote, registros.size());
            List<LogRegistro> fatia = new ArrayList<LogRegistro>(fim - i);
            for (int j = i; j < fim; j++) {
                fatia.add(registros.get(j).comId(GERADOR.proximo()));
            }
            sink.escreverLote(fatia);
        }
    }

    private static final IdGerador GERADOR = new IdGerador(0L);

    private static List<LogRegistro> gerarCarga(int quantidade) {
        String[] payloads = PayloadsReais.todos();
        List<LogRegistro> carga = new ArrayList<LogRegistro>(quantidade);
        Date agora = new Date();

        for (int i = 0; i < quantidade; i++) {
            String atual = payloads[i % payloads.length];
            String novo = payloads[(i + 1) % payloads.length];
            carga.add(LogRegistro.novo()
                    .idLogTipo(44L)
                    .idUsuario(900000L + (i % 5000))
                    .ipComputador("10.20." + (i % 250) + "." + ((i / 250) % 250))
                    .hora(agora)
                    .tabela("Processo")
                    .idTabela(String.valueOf(104620234L + i))
                    .valorAtual(atual)
                    .valorNovo(novo)
                    .codigoTemp(String.valueOf(i % 100000))
                    .construir());
        }
        return carga;
    }

    // -------------------------------------------------------------------------

    private static boolean verificar(String nome, ConexaoSupplier conexoes, String sql) {
        Connection cx = null;
        Statement st = null;
        try {
            cx = conexoes.obter();
            st = cx.createStatement();
            st.execute(sql);
            System.out.println("  " + nome + ": disponível");
            return true;
        } catch (SQLException e) {
            System.out.println("  " + nome + ": INDISPONÍVEL — " + e.getMessage());
            return false;
        } finally {
            fechar(st);
            fechar(cx);
        }
    }

    private static void executar(ConexaoSupplier conexoes, String sql) throws SQLException {
        Connection cx = null;
        Statement st = null;
        try {
            cx = conexoes.obter();
            st = cx.createStatement();
            st.execute(sql);
        } finally {
            fechar(st);
            fechar(cx);
        }
    }

    private static long contar(ConexaoSupplier conexoes, String sql) {
        Connection cx = null;
        Statement st = null;
        ResultSet rs = null;
        try {
            cx = conexoes.obter();
            st = cx.createStatement();
            rs = st.executeQuery(sql);
            return rs.next() ? rs.getLong(1) : -1L;
        } catch (SQLException e) {
            return -1L;
        } finally {
            fechar(rs);
            fechar(st);
            fechar(cx);
        }
    }

    private static void fechar(AutoCloseable recurso) {
        if (recurso != null) {
            try {
                recurso.close();
            } catch (Exception e) {
                // ignorado
            }
        }
    }

    // -------------------------------------------------------------------------

    private static void imprimirCabecalho(Parametros p, int n, int warmup, int repeticoes,
                                          List<Integer> lotes) {
        Runtime rt = Runtime.getRuntime();
        SimpleDateFormat formato = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z");

        System.out.println();
        System.out.println(LINHA);
        System.out.println("BENCHMARK — Solução 1 (offload de log): Oracle x ClickHouse");
        System.out.println(LINHA);
        System.out.println("Data/hora .......... " + formato.format(new Date()));
        System.out.println("Máquina ............ " + System.getProperty("os.name") + " "
                + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        System.out.println("Núcleos disponíveis  " + rt.availableProcessors());
        System.out.println("Heap máximo ........ " + (rt.maxMemory() / (1024 * 1024)) + " MB");
        System.out.println("JVM ................ " + System.getProperty("java.vm.name") + " "
                + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        System.out.println("Encoding ........... " + System.getProperty("file.encoding"));
        System.out.println();
        System.out.println("Registros medidos .. " + n);
        System.out.println("Warmup ............. " + warmup + " (fora do cronômetro)");
        System.out.println("Repetições ......... " + repeticoes);
        System.out.println("Tamanhos de lote ... " + lotes);
        if (p.caminhoDoEnv() != null) {
            System.out.println("Credenciais de ..... " + p.caminhoDoEnv());
        }
        System.out.println();
        System.out.println("Parâmetros efetivos (valor <- procedência):");
        for (Map.Entry<String, String> e : p.usados().entrySet()) {
            String chave = e.getKey();
            String valor = chave.contains("senha") ? "********" : e.getValue();
            System.out.printf("  %-22s %-46s <- %s%n", chave, valor, p.procedenciaDe(chave));
        }
        System.out.println();
        System.out.println("Verificando destinos:");
    }

    private static void imprimirTabela(List<Resultado> resultados, int n) {
        System.out.println();
        System.out.println(LINHA);
        System.out.println("RESULTADO — " + n + " registros por rodada, mediana de repetições");
        System.out.println(LINHA);
        System.out.printf("%-14s %8s %14s %14s %12s %12s%n",
                "Destino", "Lote", "Mediana (ms)", "Vazão (reg/s)", "Melhor (ms)", "Pior (ms)");
        System.out.println(LINHA);

        for (Resultado r : resultados) {
            double medianaMs = r.medianaNanos() / 1e6;
            // A vírgula é FLAG do printf: vem antes da largura (%,14.1f), não
            // depois (%14,.1f) — este último lança UnknownFormatConversion.
            System.out.printf("%-14s %8d %,14.1f %,14.0f %,12.1f %,12.1f%n",
                    r.destino, Integer.valueOf(r.lote),
                    Double.valueOf(medianaMs),
                    Double.valueOf(n / (r.medianaNanos() / 1e9)),
                    Double.valueOf(r.menorNanos() / 1e6),
                    Double.valueOf(r.maiorNanos() / 1e6));
        }
        System.out.println(LINHA);

        // Razão por tamanho de lote, que é o número que vai para o texto.
        boolean temParDeComparacao = false;
        for (Resultado a : resultados) {
            if (!"Oracle".equals(a.destino)) {
                continue;
            }
            for (Resultado b : resultados) {
                if ("ClickHouse".equals(b.destino) && b.lote == a.lote) {
                    temParDeComparacao = true;
                }
            }
        }
        if (!temParDeComparacao) {
            return;
        }
        System.out.println();
        System.out.println("Razão Oracle / ClickHouse (>1 significa ClickHouse mais rápido):");
        for (Resultado a : resultados) {
            if (!"Oracle".equals(a.destino)) {
                continue;
            }
            for (Resultado b : resultados) {
                if ("ClickHouse".equals(b.destino) && b.lote == a.lote) {
                    double razao = (double) a.medianaNanos() / (double) b.medianaNanos();
                    System.out.printf("  lote=%-6d %.2fx%n", Integer.valueOf(a.lote), Double.valueOf(razao));
                }
            }
        }
    }

    private static void conferirCompletude(ConexaoSupplier ch, ConexaoSupplier oracle) {
        System.out.println();
        System.out.println("Completude (linhas x ID_LOG distintos — precisam bater):");
        if (ch != null) {
            long linhas = contar(ch, "SELECT count() FROM projudi_logs.log_raw");
            long distintos = contar(ch, "SELECT uniqExact(ID_LOG) FROM projudi_logs.log_raw");
            System.out.printf("  ClickHouse   %,12d linhas   %,12d IDs distintos   %s%n",
                    Long.valueOf(linhas), Long.valueOf(distintos),
                    linhas == distintos ? "OK" : "DIVERGENTE");
        }
        if (oracle != null) {
            long linhas = contar(oracle, "SELECT count(*) FROM PROJUDI.LOG");
            long distintos = contar(oracle, "SELECT count(DISTINCT ID_LOG) FROM PROJUDI.LOG");
            System.out.printf("  Oracle       %,12d linhas   %,12d IDs distintos   %s%n",
                    Long.valueOf(linhas), Long.valueOf(distintos),
                    linhas == distintos ? "OK" : "DIVERGENTE");
        }
    }

    private static void imprimirRodape() {
        System.out.println();
        System.out.println(LINHA);
        System.out.println("Ressalvas metodológicas que precisam acompanhar estes números:");
        System.out.println();
        System.out.println("1. Os dois destinos estão em containers no MESMO host. Comparar um");
        System.out.println("   destino em LAN corporativa com outro pela internet mediria rede,");
        System.out.println("   não banco (docs/ambientes.md, seção 3).");
        System.out.println("2. O Oracle aqui está com os índices de PROJUDI.LOG criados");
        System.out.println("   (decisão 13): sem eles, a comparação seria desonesta.");
        System.out.println("3. Isto mede o CAMINHO DE ESCRITA de um cliente. Não mede o efeito");
        System.out.println("   de tirar a carga do banco transacional, que é o ganho real da");
        System.out.println("   Solução 1 e depende de medição no ambiente de produção.");
        System.out.println("4. Lote=1 é o modo síncrono, comparável ao que a LogPs faz hoje.");
        System.out.println("   Lotes maiores só são atingíveis com a fila do BufferedLogSink,");
        System.out.println("   cuja janela de perda está registrada em docs/decisoes.md, dec. 19.");
        System.out.println(LINHA);
    }

    private static void imprimirAjuda() {
        System.out.println("Harness de benchmark do log-writer — Oracle x ClickHouse");
        System.out.println();
        System.out.println("  mvn -q test-compile exec:java@bench -Dbench.n=20000");
        System.out.println();
        System.out.println("Parâmetros (system property ou --chave=valor):");
        System.out.println("  bench.n              registros medidos por rodada      (10000)");
        System.out.println("  bench.warmup         registros de aquecimento          (1000)");
        System.out.println("  bench.repeticoes     rodadas por configuração          (3)");
        System.out.println("  bench.lotes          tamanhos de lote, separados por , (1,100,500,2000)");
        System.out.println("  bench.limpar         esvazia as tabelas antes          (true)");
        System.out.println("  bench.oracle         inclui o grupo de controle        (true)");
        System.out.println("  bench.clickhouse     inclui o ClickHouse               (true)");
        System.out.println("  bench.seco           grava em memoria, sem banco       (false)");
        System.out.println("  bench.saida          espelha o relatorio num arquivo UTF-8");
        System.out.println("  bench.ch.url         (jdbc:ch://localhost:8123/projudi_logs)");
        System.out.println("  bench.ch.usuario     (.env CH_APP_USER, ou projudi_app)");
        System.out.println("  bench.ch.senha       (.env CH_APP_PASSWORD)");
        System.out.println("  bench.oracle.url     (jdbc:oracle:thin:@//localhost:1521/FREEPDB1)");
        System.out.println("  bench.oracle.usuario (PROJUDI)");
        System.out.println("  bench.oracle.senha   (.env ORACLE_PROJUDI_PASSWORD)");
    }
}
