package br.jus.tjgo.projudi.logwriter.sink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import br.jus.tjgo.projudi.logwriter.LogRegistro;
import br.jus.tjgo.projudi.logwriter.LogWriterException;
import br.jus.tjgo.projudi.logwriter.apoio.JdbcFalso;
import br.jus.tjgo.projudi.logwriter.apoio.PayloadsReais;
import br.jus.tjgo.projudi.logwriter.logtipo.LogTipoResolver;

/**
 * Verifica o SQL e a ligação de parâmetros dos dois sinks JDBC sem nenhum banco
 * de pé, com o {@link JdbcFalso}.
 */
class SinksJdbcTest {

    private static final long ID = 870123456789012345L;

    private static LogRegistro completo() {
        Date hora = new Date(1_784_000_000_000L);
        return LogRegistro.novo()
                .idLog(ID)
                .idLogTipo(44L)
                .idUsuario(998877L)
                .ipComputador("10.20.30.40")
                .hora(hora)
                .tabela("Processo")
                .valorAtual(PayloadsReais.PROPRIEDADES_ANTERIOR)
                .valorNovo(PayloadsReais.PROPRIEDADES)
                .codigoTemp("54321")
                .idTabela("104620234")
                .hash("d41d8cd98f00b204e9800998ecf8427e")
                .qtdErrosDia(Integer.valueOf(3))
                .construir();
    }

    private static LogRegistro minimo() {
        return LogRegistro.novo().idLog(ID + 1L).idUsuario(1L).construir();
    }

    // ---------------------------------------------------------------- ClickHouse

    @Test
    @DisplayName("ClickHouse: INSERT fixo com as 13 colunas da log_raw, na ordem do DDL")
    void clickHouseSqlFixo() throws Exception {
        JdbcFalso jdbc = new JdbcFalso();
        ClickHouseLogSink sink = new ClickHouseLogSink(jdbc);

        try {
            sink.escrever(completo());
        } finally {
            sink.close();
        }

        assertEquals(1, jdbc.getSqlsPreparados().size());
        String sql = jdbc.getSqlsPreparados().get(0);
        assertEquals("INSERT INTO projudi_logs.log_raw ("
                + "ID_LOG, ID_LOG_TIPO, ID_USU, IP_COMPUTADOR, DATA, HORA, TABELA, "
                + "VALOR_ATUAL, VALOR_NOVO, CODIGO_TEMP, ID_TABELA, HASH, QTD_ERROS_DIA"
                + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", sql);
        assertEquals(sql, sink.getSql());
    }

    @Test
    @DisplayName("ClickHouse: cada coluna recebe o valor e o tipo certos")
    void clickHouseLigacaoDeParametros() throws Exception {
        JdbcFalso jdbc = new JdbcFalso();
        ClickHouseLogSink sink = new ClickHouseLogSink(jdbc);
        LogRegistro r = completo();

        try {
            sink.escrever(r);
        } finally {
            sink.close();
        }

        assertEquals(1, jdbc.getLinhas().size());
        JdbcFalso.Linha linha = jdbc.getLinhas().get(0);
        assertEquals(13, linha.quantidadeParametros());

        assertEquals(Long.valueOf(ID), linha.valor(1));
        assertEquals(Long.valueOf(44L), linha.valor(2));
        assertEquals(Long.valueOf(998877L), linha.valor(3));
        assertEquals("10.20.30.40", linha.valor(4));
        assertEquals(new Timestamp(r.getData().getTime()), linha.valor(5));
        assertEquals(new Timestamp(r.getHora().getTime()), linha.valor(6));
        assertEquals("Processo", linha.valor(7));
        assertEquals(PayloadsReais.PROPRIEDADES_ANTERIOR, linha.valor(8));
        assertEquals(PayloadsReais.PROPRIEDADES, linha.valor(9));
        assertEquals(Long.valueOf(54321L), linha.valor(10));
        assertEquals(Long.valueOf(104620234L), linha.valor(11));
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", linha.valor(12));
        assertEquals(Integer.valueOf(3), linha.valor(13));

        // DATA é DateTime na log_raw, não Date: precisa ir como Timestamp.
        assertEquals("setTimestamp", linha.metodo(5));
        assertEquals("setTimestamp", linha.metodo(6));
    }

    @Test
    @DisplayName("ClickHouse: colunas Nullable ausentes vão como NULL; String vão como ''")
    void clickHouseAusencias() throws Exception {
        JdbcFalso jdbc = new JdbcFalso();
        ClickHouseLogSink sink = new ClickHouseLogSink(jdbc);

        try {
            sink.escrever(minimo());
        } finally {
            sink.close();
        }

        JdbcFalso.Linha linha = jdbc.getLinhas().get(0);

        // Não-Nullable no DDL: '' representa ausência.
        assertEquals("", linha.valor(4));
        assertEquals("", linha.valor(7));
        assertEquals("", linha.valor(8));
        assertEquals("", linha.valor(9));
        assertEquals("setString", linha.metodo(4));

        // Nullable no DDL: NULL de verdade.
        assertNull(linha.valor(10));
        assertEquals("setNull", linha.metodo(10));
        assertEquals("setNull", linha.metodo(11));
        assertEquals("setNull", linha.metodo(12));
        assertEquals("setNull", linha.metodo(13));
    }

    @Test
    @DisplayName("ClickHouse: o lote vira um único executeBatch")
    void clickHouseLoteUnico() throws Exception {
        JdbcFalso jdbc = new JdbcFalso();
        ClickHouseLogSink sink = new ClickHouseLogSink(jdbc);

        List<LogRegistro> lote = new ArrayList<LogRegistro>();
        for (int i = 0; i < 250; i++) {
            lote.add(LogRegistro.novo().idLog(ID + i).idUsuario(1L).construir());
        }

        try {
            sink.escreverLote(lote);
        } finally {
            sink.close();
        }

        assertEquals(1, jdbc.getSqlsPreparados().size(), "um PreparedStatement para o lote inteiro");
        assertEquals(1, jdbc.getTamanhosDeLote().size());
        assertEquals(Integer.valueOf(250), jdbc.getTamanhosDeLote().get(0));
        assertEquals(250, jdbc.getLinhas().size());
        assertEquals(250L, sink.metricas().getGravadosDestino());
    }

    @Test
    @DisplayName("ClickHouse: falha vira LogWriterException e descarta a conexão")
    void clickHouseFalhaDescartaConexao() throws Exception {
        final JdbcFalso jdbc = new JdbcFalso();
        final ClickHouseLogSink sink = new ClickHouseLogSink(jdbc);

        try {
            jdbc.falharNoExecuteBatch("connection refused");
            assertThrows(LogWriterException.class, new Executable() {
                @Override
                public void execute() throws Throwable {
                    sink.escrever(completo());
                }
            });
            assertEquals(1L, sink.metricas().getLotesComFalha());

            // A conexão defeituosa foi descartada: a gravação seguinte abre outra.
            int aposFalha = jdbc.getConexoesAbertas();
            jdbc.falharNoExecuteBatch(null);
            sink.escrever(completo());
            assertEquals(aposFalha + 1, jdbc.getConexoesAbertas(),
                    "após a falha, uma conexão nova precisa ser aberta");
        } finally {
            sink.close();
        }
    }

    @Test
    @DisplayName("ClickHouse: a conexão é reaproveitada entre lotes bem-sucedidos")
    void clickHouseReaproveitaConexao() throws Exception {
        JdbcFalso jdbc = new JdbcFalso();
        ClickHouseLogSink sink = new ClickHouseLogSink(jdbc);

        try {
            for (int i = 0; i < 10; i++) {
                sink.escrever(minimo());
            }
        } finally {
            sink.close();
        }
        assertEquals(1, jdbc.getConexoesAbertas(),
                "10 gravações não podem abrir 10 conexões");
    }

    @Test
    @DisplayName("ClickHouse: ID_LOG_TIPO ausente é resolvido pelo código de negócio")
    void clickHouseResolveLogTipoCodigo() throws Exception {
        JdbcFalso jdbc = new JdbcFalso();
        LogTipoResolver resolver = new LogTipoResolver() {
            @Override
            public long resolver(long logTipoCodigo) {
                return (logTipoCodigo == 28L) ? 44L : 0L;
            }
        };
        ClickHouseLogSink sink = new ClickHouseLogSink(jdbc, ClickHouseLogSink.TABELA_PADRAO, resolver);

        try {
            // Caminho do subselect da LogPs: só logTipoCodigo, sem idLogTipo.
            sink.escrever(LogRegistro.novo().idLog(ID).idUsuario(1L).logTipoCodigo(28L).construir());
            // Quando o idLogTipo já vem pronto, o resolver não é consultado.
            sink.escrever(LogRegistro.novo().idLog(ID + 1).idUsuario(1L)
                    .idLogTipo(99L).logTipoCodigo(28L).construir());
            // Código desconhecido: grava 0 em vez de perder o registro.
            sink.escrever(LogRegistro.novo().idLog(ID + 2).idUsuario(1L).logTipoCodigo(7777L).construir());
        } finally {
            sink.close();
        }

        assertEquals(Long.valueOf(44L), jdbc.getLinhas().get(0).valor(2));
        assertEquals(Long.valueOf(99L), jdbc.getLinhas().get(1).valor(2));
        assertEquals(Long.valueOf(0L), jdbc.getLinhas().get(2).valor(2));
    }

    // -------------------------------------------------------------------- Oracle

    @Test
    @DisplayName("Oracle: 13 colunas no laboratório, 11 em produção")
    void oracleSqlConformeATabela() {
        assertEquals("INSERT INTO PROJUDI.LOG ("
                        + "ID_LOG, ID_LOG_TIPO, ID_USU, IP_COMPUTADOR, DATA, HORA, TABELA, "
                        + "VALOR_ATUAL, VALOR_NOVO, CODIGO_TEMP, ID_TABELA, HASH, QTD_ERROS_DIA"
                        + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                new OracleLogSink(new JdbcFalso()).getSql());

        assertEquals("INSERT INTO PROJUDI.LOG ("
                        + "ID_LOG, ID_LOG_TIPO, ID_USU, IP_COMPUTADOR, DATA, HORA, TABELA, "
                        + "VALOR_ATUAL, VALOR_NOVO, CODIGO_TEMP, ID_TABELA"
                        + ") VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                new OracleLogSink(new JdbcFalso(), "PROJUDI.LOG", false).getSql());
    }

    @Test
    @DisplayName("Oracle: ID_LOG vai explícito — a trigger só preenche quando vem NULL")
    void oracleGravaIdExplicito() throws Exception {
        JdbcFalso jdbc = new JdbcFalso();
        OracleLogSink sink = new OracleLogSink(jdbc);

        try {
            sink.escrever(completo());
        } finally {
            sink.close();
        }

        JdbcFalso.Linha linha = jdbc.getLinhas().get(0);
        assertEquals(Long.valueOf(ID), linha.valor(1));
        assertEquals("setLong", linha.metodo(1),
                "o ID precisa ir preenchido: LOG_ID_LOG_TRG só atribui IF :new.Id_Log IS NULL, "
                        + "então enviá-lo não consome LOG_ID_LOG_SEQ nem afeta a numeração legada");
    }

    @Test
    @DisplayName("Oracle: DATA vai como Date e HORA como Timestamp, igual à LogPs")
    void oracleTiposTemporais() throws Exception {
        JdbcFalso jdbc = new JdbcFalso();
        OracleLogSink sink = new OracleLogSink(jdbc);

        try {
            sink.escrever(completo());
        } finally {
            sink.close();
        }

        JdbcFalso.Linha linha = jdbc.getLinhas().get(0);
        // LogPs: ps.adicionarDate(...) na DATA, ps.adicionarDateTime(...) na HORA.
        assertEquals("setDate", linha.metodo(5));
        assertEquals("setTimestamp", linha.metodo(6));
        // CLOBs ligados com setString, como Persistencia faz no case Types.CLOB.
        assertEquals("setString", linha.metodo(8));
        assertEquals("setString", linha.metodo(9));
    }

    @Test
    @DisplayName("Oracle: lote sai com autocommit desligado e um commit por lote")
    void oracleCommitPorLote() throws Exception {
        JdbcFalso jdbc = new JdbcFalso();
        OracleLogSink sink = new OracleLogSink(jdbc);

        List<LogRegistro> lote = new ArrayList<LogRegistro>();
        for (int i = 0; i < 100; i++) {
            lote.add(LogRegistro.novo().idLog(ID + i).idUsuario(1L).construir());
        }

        try {
            sink.escreverLote(lote);
            sink.escreverLote(lote);
        } finally {
            sink.close();
        }

        assertTrue(!jdbc.isAutoCommit(), "o lote precisa rodar com autocommit desligado");
        assertEquals(2, jdbc.getCommits(), "um commit por lote");
        assertEquals(0, jdbc.getRollbacks());
        assertEquals(2, jdbc.getTamanhosDeLote().size());
        assertEquals(Integer.valueOf(100), jdbc.getTamanhosDeLote().get(0));
    }

    @Test
    @DisplayName("Oracle: falha no lote faz rollback antes de propagar")
    void oracleRollbackNaFalha() throws Exception {
        final JdbcFalso jdbc = new JdbcFalso();
        final OracleLogSink sink = new OracleLogSink(jdbc);

        try {
            jdbc.falharNoExecuteBatch("ORA-00001: unique constraint violated");
            assertThrows(LogWriterException.class, new Executable() {
                @Override
                public void execute() throws Throwable {
                    sink.escrever(completo());
                }
            });
            assertEquals(1, jdbc.getRollbacks());
            assertEquals(0, jdbc.getCommits());
        } finally {
            sink.close();
        }
    }

    @Test
    @DisplayName("os dois sinks gravam os CLOBs idênticos ao que receberam")
    void clobsIdenticosNosDoisSinks() throws Exception {
        for (String payload : PayloadsReais.todos()) {
            LogRegistro r = LogRegistro.novo()
                    .idLog(ID).idUsuario(1L).valorAtual(payload).valorNovo(payload).construir();

            JdbcFalso ch = new JdbcFalso();
            ClickHouseLogSink sinkCh = new ClickHouseLogSink(ch);
            try {
                sinkCh.escrever(r);
            } finally {
                sinkCh.close();
            }

            JdbcFalso ora = new JdbcFalso();
            OracleLogSink sinkOra = new OracleLogSink(ora);
            try {
                sinkOra.escrever(r);
            } finally {
                sinkOra.close();
            }

            assertEquals(payload, ch.getLinhas().get(0).valor(8));
            assertEquals(payload, ch.getLinhas().get(0).valor(9));
            assertEquals(payload, ora.getLinhas().get(0).valor(8));
            assertEquals(payload, ora.getLinhas().get(0).valor(9));
        }
    }
}
