package br.jus.tjgo.projudi.logwriter;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Fonte de conexões JDBC.
 *
 * <p>É a costura que torna os sinks testáveis sem banco: o teste injeta um
 * supplier que devolve uma conexão de mentira e verifica o SQL e os parâmetros
 * ligados, sem ClickHouse nem Oracle de pé.</p>
 *
 * <p>Também é o ponto de extensão para o Projudi usar o pool que já existe lá
 * ({@code FabricaConexao}) em vez do {@code DriverManager}, se um dia a
 * gravação de log passar a compartilhar pool com o resto da aplicação.</p>
 */
public interface ConexaoSupplier {

    Connection obter() throws SQLException;

    /** Implementação padrão sobre {@link DriverManager}. */
    final class DoDriverManager implements ConexaoSupplier {

        private final String url;
        private final String usuario;
        private final String senha;

        public DoDriverManager(String url, String usuario, String senha) {
            this.url = url;
            this.usuario = usuario;
            this.senha = (senha == null) ? "" : senha;
        }

        @Override
        public Connection obter() throws SQLException {
            Properties props = new Properties();
            if (usuario != null) {
                props.setProperty("user", usuario);
            }
            props.setProperty("password", senha);
            return DriverManager.getConnection(url, props);
        }

        @Override
        public String toString() {
            return "DriverManager[" + url + " como " + usuario + "]";
        }
    }
}
