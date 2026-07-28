package br.jus.tjgo.projudi.logwriter.bench;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;

/**
 * Espelha a saída do harness num arquivo UTF-8, além do console.
 *
 * <p>Não é conveniência: o relatório do TCC precisa do texto com acentuação
 * correta, e o console do Windows roda em Cp1252 por padrão — a saída aparece
 * ilegível ali mesmo estando certa na memória. Escrever o mesmo conteúdo num
 * arquivo com encoding fixo resolve, e ainda deixa a evidência versionável em
 * {@code validacao/evidencias/}.</p>
 */
final class SaidaDupla extends OutputStream {

    private final OutputStream console;
    private final OutputStream arquivo;

    private SaidaDupla(OutputStream console, OutputStream arquivo) {
        this.console = console;
        this.arquivo = arquivo;
    }

    /**
     * Substitui {@code System.out} por um espelho console + arquivo.
     *
     * @return o {@code System.out} original, para restauração
     */
    static PrintStream instalar(String caminho) throws IOException {
        PrintStream original = System.out;
        OutputStream saidaArquivo = new FileOutputStream(caminho);
        PrintStream espelho = new PrintStream(
                new SaidaDupla(original, saidaArquivo), true, "UTF-8");
        System.setOut(espelho);
        return original;
    }

    @Override
    public void write(int b) throws IOException {
        console.write(b);
        arquivo.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        console.write(b, off, len);
        arquivo.write(b, off, len);
    }

    @Override
    public void flush() throws IOException {
        console.flush();
        arquivo.flush();
    }

    @Override
    public void close() throws IOException {
        // O console não é fechado: ele não pertence a esta classe.
        arquivo.flush();
        arquivo.close();
    }
}
