package br.jus.tjgo.projudi.logwriter.apoio;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import br.jus.tjgo.projudi.logwriter.ConexaoSupplier;

/**
 * {@link Connection} e {@link PreparedStatement} de mentira, construídos com
 * {@link Proxy}.
 *
 * <p>Existe para que o SQL e a ligação de parâmetros dos sinks JDBC sejam
 * verificados de verdade — coluna a coluna, na ordem certa, com o tipo certo —
 * <b>sem ClickHouse nem Oracle de pé</b>. Implementar {@code PreparedStatement}
 * à mão significaria escrever mais de 50 métodos vazios; o proxy dinâmico
 * registra tudo em quatro linhas e ainda sobrevive a mudanças de assinatura
 * entre versões do JDBC.</p>
 */
public final class JdbcFalso implements ConexaoSupplier {

    /** Uma chamada {@code setXxx(indice, valor)} registrada. */
    public static final class Parametro {
        public final String metodo;
        public final int indice;
        public final Object valor;

        Parametro(String metodo, int indice, Object valor) {
            this.metodo = metodo;
            this.indice = indice;
            this.valor = valor;
        }

        @Override
        public String toString() {
            return metodo + "(" + indice + ", " + valor + ")";
        }
    }

    /** Uma linha do lote: o mapa índice → parâmetro no momento do addBatch. */
    public static final class Linha {
        private final Map<Integer, Parametro> porIndice;

        Linha(Map<Integer, Parametro> porIndice) {
            this.porIndice = new LinkedHashMap<Integer, Parametro>(porIndice);
        }

        public Object valor(int indice) {
            Parametro p = porIndice.get(Integer.valueOf(indice));
            return (p == null) ? null : p.valor;
        }

        public String metodo(int indice) {
            Parametro p = porIndice.get(Integer.valueOf(indice));
            return (p == null) ? null : p.metodo;
        }

        public int quantidadeParametros() {
            return porIndice.size();
        }

        @Override
        public String toString() {
            return porIndice.values().toString();
        }
    }

    private final List<String> sqlsPreparados = Collections.synchronizedList(new ArrayList<String>());
    private final List<Linha> linhas = Collections.synchronizedList(new ArrayList<Linha>());
    private final List<Integer> tamanhosDeLote = Collections.synchronizedList(new ArrayList<Integer>());

    private volatile SQLException falhaNoExecuteBatch;
    private volatile SQLException falhaNaConexao;
    private volatile int conexoesAbertas;
    private volatile int commits;
    private volatile int rollbacks;
    private volatile boolean autoCommit = true;

    @Override
    public Connection obter() throws SQLException {
        if (falhaNaConexao != null) {
            throw falhaNaConexao;
        }
        conexoesAbertas++;
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new ManipuladorConexao());
    }

    /** Faz o próximo {@code executeBatch} falhar. {@code null} desliga. */
    public void falharNoExecuteBatch(String mensagem) {
        this.falhaNoExecuteBatch = (mensagem == null) ? null : new SQLException(mensagem);
    }

    /** Faz {@code obter()} falhar. {@code null} desliga. */
    public void falharAoConectar(String mensagem) {
        this.falhaNaConexao = (mensagem == null) ? null : new SQLException(mensagem);
    }

    public List<String> getSqlsPreparados() {
        return new ArrayList<String>(sqlsPreparados);
    }

    /** Todas as linhas ligadas via {@code addBatch}, em ordem. */
    public List<Linha> getLinhas() {
        return new ArrayList<Linha>(linhas);
    }

    /** Tamanho de cada {@code executeBatch} executado, em ordem. */
    public List<Integer> getTamanhosDeLote() {
        return new ArrayList<Integer>(tamanhosDeLote);
    }

    public int getConexoesAbertas() {
        return conexoesAbertas;
    }

    public int getCommits() {
        return commits;
    }

    public int getRollbacks() {
        return rollbacks;
    }

    public boolean isAutoCommit() {
        return autoCommit;
    }

    public void limpar() {
        sqlsPreparados.clear();
        linhas.clear();
        tamanhosDeLote.clear();
    }

    // -------------------------------------------------------------------------

    private final class ManipuladorConexao implements InvocationHandler {

        private boolean fechada;

        @Override
        public Object invoke(Object proxy, Method metodo, Object[] args) throws Throwable {
            String nome = metodo.getName();
            if ("prepareStatement".equals(nome)) {
                String sql = (String) args[0];
                sqlsPreparados.add(sql);
                return Proxy.newProxyInstance(
                        PreparedStatement.class.getClassLoader(),
                        new Class<?>[]{PreparedStatement.class},
                        new ManipuladorStatement());
            }
            if ("isClosed".equals(nome)) {
                return Boolean.valueOf(fechada);
            }
            if ("close".equals(nome)) {
                fechada = true;
                return null;
            }
            if ("setAutoCommit".equals(nome)) {
                autoCommit = ((Boolean) args[0]).booleanValue();
                return null;
            }
            if ("getAutoCommit".equals(nome)) {
                return Boolean.valueOf(autoCommit);
            }
            if ("commit".equals(nome)) {
                commits++;
                return null;
            }
            if ("rollback".equals(nome)) {
                rollbacks++;
                return null;
            }
            return padraoPara(metodo);
        }
    }

    private final class ManipuladorStatement implements InvocationHandler {

        private final Map<Integer, Parametro> atual = new LinkedHashMap<Integer, Parametro>();
        private int noLote;

        @Override
        public Object invoke(Object proxy, Method metodo, Object[] args) throws Throwable {
            String nome = metodo.getName();

            if (nome.startsWith("set") && args != null && args.length >= 2
                    && args[0] instanceof Integer) {
                int indice = ((Integer) args[0]).intValue();
                Object valor = "setNull".equals(nome) ? null : args[1];
                atual.put(Integer.valueOf(indice), new Parametro(nome, indice, valor));
                return null;
            }
            if ("addBatch".equals(nome)) {
                linhas.add(new Linha(atual));
                noLote++;
                return null;
            }
            if ("executeBatch".equals(nome)) {
                SQLException falha = falhaNoExecuteBatch;
                if (falha != null) {
                    throw falha;
                }
                tamanhosDeLote.add(Integer.valueOf(noLote));
                int[] resultado = new int[noLote];
                noLote = 0;
                return resultado;
            }
            if ("close".equals(nome)) {
                return null;
            }
            return padraoPara(metodo);
        }
    }

    /** Valor neutro para os métodos que o teste não exercita. */
    private static Object padraoPara(Method metodo) {
        Class<?> retorno = metodo.getReturnType();
        if (!retorno.isPrimitive()) {
            return null;
        }
        if (retorno == boolean.class) {
            return Boolean.FALSE;
        }
        if (retorno == void.class) {
            return null;
        }
        if (retorno == int.class) {
            return Integer.valueOf(0);
        }
        if (retorno == long.class) {
            return Long.valueOf(0L);
        }
        if (retorno == short.class) {
            return Short.valueOf((short) 0);
        }
        if (retorno == byte.class) {
            return Byte.valueOf((byte) 0);
        }
        if (retorno == char.class) {
            return Character.valueOf((char) 0);
        }
        if (retorno == float.class) {
            return Float.valueOf(0f);
        }
        return Double.valueOf(0d);
    }

    @Override
    public String toString() {
        return "JdbcFalso[" + linhas.size() + " linha(s), " + tamanhosDeLote.size() + " lote(s)]";
    }
}
