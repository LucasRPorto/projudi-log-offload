package br.jus.tjgo.projudi.logwriter.bench;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parâmetros do harness, resolvidos nesta ordem: argumento de linha de comando
 * → system property → variável de ambiente → arquivo {@code .env} da raiz do
 * repositório → padrão embutido.
 *
 * <p>A ordem importa para a metodologia: <b>tudo o que afeta a medição precisa
 * ser explícito na invocação</b> e sair impresso no cabeçalho do relatório. O
 * {@code .env} entra só para credenciais, que não mudam o resultado, e a
 * procedência de cada valor é registrada para constar na saída.</p>
 */
final class Parametros {

    private final Map<String, String> valores = new LinkedHashMap<String, String>();
    private final Map<String, String> procedencia = new LinkedHashMap<String, String>();
    private final Map<String, String> cli = new LinkedHashMap<String, String>();
    private final Map<String, String> env = new LinkedHashMap<String, String>();

    Parametros(String[] args) {
        for (String arg : args) {
            if (arg == null) {
                continue;
            }
            String limpo = arg.startsWith("--") ? arg.substring(2) : arg;
            int igual = limpo.indexOf('=');
            if (igual > 0) {
                cli.put(limpo.substring(0, igual).trim(), limpo.substring(igual + 1).trim());
            }
        }
        carregarEnv();
    }

    /**
     * Lê o {@code .env} da raiz do repositório, se existir. Procura a partir do
     * diretório de trabalho e sobe até três níveis, porque o harness pode ser
     * invocado da raiz ou de dentro de {@code log-writer/}.
     */
    private void carregarEnv() {
        File dir = new File(System.getProperty("user.dir"));
        for (int i = 0; i < 4 && dir != null; i++) {
            File candidato = new File(dir, ".env");
            if (candidato.isFile()) {
                lerArquivo(candidato);
                env.put("__arquivo__", candidato.getAbsolutePath());
                return;
            }
            dir = dir.getParentFile();
        }
    }

    private void lerArquivo(File arquivo) {
        BufferedReader leitor = null;
        try {
            leitor = new BufferedReader(
                    new InputStreamReader(new FileInputStream(arquivo), "UTF-8"));
            String linha;
            while ((linha = leitor.readLine()) != null) {
                String limpa = linha.trim();
                if (limpa.isEmpty() || limpa.startsWith("#")) {
                    continue;
                }
                int igual = limpa.indexOf('=');
                if (igual > 0) {
                    env.put(limpa.substring(0, igual).trim(), limpa.substring(igual + 1).trim());
                }
            }
        } catch (IOException e) {
            // .env é conveniência; a ausência ou a ilegibilidade não é erro.
        } finally {
            if (leitor != null) {
                try {
                    leitor.close();
                } catch (IOException e) {
                    // ignorado
                }
            }
        }
    }

    String caminhoDoEnv() {
        return env.get("__arquivo__");
    }

    /**
     * @param chave     nome do parâmetro, ex. {@code bench.n}
     * @param chaveEnv  nome no {@code .env}, ou {@code null} se não vier de lá
     */
    String texto(String chave, String chaveEnv, String padrao) {
        String valor = cli.get(chave);
        String de = "linha de comando";

        if (valor == null) {
            valor = System.getProperty(chave);
            de = "system property";
        }
        if (valor == null || valor.trim().isEmpty()) {
            valor = System.getenv(chave.replace('.', '_').toUpperCase());
            de = "variável de ambiente";
        }
        if ((valor == null || valor.trim().isEmpty()) && chaveEnv != null) {
            valor = env.get(chaveEnv);
            de = ".env (" + chaveEnv + ")";
        }
        if (valor == null || valor.trim().isEmpty()) {
            valor = padrao;
            de = "padrão";
        }
        valores.put(chave, valor);
        procedencia.put(chave, de);
        return valor;
    }

    int inteiro(String chave, int padrao) {
        String valor = texto(chave, null, String.valueOf(padrao));
        try {
            return Integer.parseInt(valor.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Valor inválido para " + chave + ": " + valor, e);
        }
    }

    boolean booleano(String chave, boolean padrao) {
        return Boolean.parseBoolean(texto(chave, null, String.valueOf(padrao)).trim());
    }

    /** Lista de inteiros separada por vírgula, ex. {@code 1,100,500}. */
    List<Integer> listaDeInteiros(String chave, String padrao) {
        String valor = texto(chave, null, padrao);
        List<Integer> lista = new ArrayList<Integer>();
        for (String parte : valor.split(",")) {
            String limpa = parte.trim();
            if (!limpa.isEmpty()) {
                lista.add(Integer.valueOf(limpa));
            }
        }
        if (lista.isEmpty()) {
            throw new IllegalArgumentException("Lista vazia em " + chave);
        }
        return lista;
    }

    /** Todos os parâmetros usados, para o cabeçalho do relatório. */
    Map<String, String> usados() {
        return new LinkedHashMap<String, String>(valores);
    }

    String procedenciaDe(String chave) {
        return procedencia.get(chave);
    }
}
