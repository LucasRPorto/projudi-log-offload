package br.jus.tjgo.projudi.logwriter.logtipo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

import br.jus.tjgo.projudi.logwriter.ConexaoSupplier;
import br.jus.tjgo.projudi.logwriter.LogWriterException;

/**
 * Resolve o {@code ID_LOG_TIPO} contra a dimensão
 * {@code projudi_logs.log_tipo} do ClickHouse.
 *
 * <p>A consulta usa {@code MAX(ID_LOG_TIPO)} pela mesma razão que o subselect
 * original da LogPs usa: {@code LOG_TIPO_CODIGO} não é único na tabela de
 * origem, e a convenção do sistema é que vale o maior id — o cadastro mais
 * recente daquele código.</p>
 *
 * <p>Deve ser embrulhado num {@link CacheLogTipoResolver}; sozinho, consulta a
 * cada chamada.</p>
 */
public final class ClickHouseLogTipoResolver implements LogTipoResolver {

    private static final Logger LOG = Logger.getLogger(ClickHouseLogTipoResolver.class.getName());

    private static final String SQL =
            "SELECT max(ID_LOG_TIPO) FROM projudi_logs.log_tipo WHERE LOG_TIPO_CODIGO = ?";

    private final ConexaoSupplier conexoes;

    public ClickHouseLogTipoResolver(ConexaoSupplier conexoes) {
        if (conexoes == null) {
            throw new IllegalArgumentException("conexoes não pode ser nulo");
        }
        this.conexoes = conexoes;
    }

    /** Envolvido em cache — a forma como isto deve ser usado na prática. */
    public static LogTipoResolver comCache(ConexaoSupplier conexoes) {
        return new CacheLogTipoResolver(new ClickHouseLogTipoResolver(conexoes));
    }

    @Override
    public long resolver(long logTipoCodigo) {
        Connection cx = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            cx = conexoes.obter();
            ps = cx.prepareStatement(SQL);
            ps.setLong(1, logTipoCodigo);
            rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0L;
        } catch (SQLException e) {
            // Falha na dimensão não pode custar o registro de auditoria.
            LOG.log(Level.WARNING,
                    "Não foi possível resolver LOG_TIPO_CODIGO=" + logTipoCodigo
                            + "; o registro será gravado com ID_LOG_TIPO=0", e);
            return 0L;
        } catch (LogWriterException e) {
            // Driver ausente no classpath. Mesmo tratamento: a dimensão é
            // acessório, o registro de auditoria não.
            LOG.log(Level.WARNING,
                    "Não foi possível resolver LOG_TIPO_CODIGO=" + logTipoCodigo
                            + "; o registro será gravado com ID_LOG_TIPO=0", e);
            return 0L;
        } finally {
            fechar(rs);
            fechar(ps);
            fechar(cx);
        }
    }

    private static void fechar(AutoCloseable recurso) {
        if (recurso != null) {
            try {
                recurso.close();
            } catch (Exception e) {
                LOG.log(Level.FINE, "Erro ao fechar recurso JDBC da dimensão log_tipo", e);
            }
        }
    }

    @Override
    public String toString() {
        return "ClickHouseLogTipoResolver[" + conexoes + "]";
    }
}
