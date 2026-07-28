package br.jus.tjgo.projudi.logwriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Calendar;
import java.util.Date;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import br.jus.tjgo.projudi.logwriter.apoio.PayloadsReais;

class LogRegistroTest {

    @Test
    @DisplayName("o Builder replica o truncamento de TABELA da LogPs")
    void truncamentoDaTabela() {
        // LogPs: if (tabela.trim().length() > 60) substring(0, 59) else trim()
        String comprimento59 = repetir('a', 59);
        String comprimento60 = repetir('b', 60);
        String comprimento61 = repetir('c', 61);
        String comprimento200 = repetir('d', 200);

        assertEquals(59, base().tabela(comprimento59).construir().getTabela().length());
        assertEquals(60, base().tabela(comprimento60).construir().getTabela().length(),
                "comprimento exatamente 60 passa inteiro — a condição da LogPs é > 60");
        assertEquals(59, base().tabela(comprimento61).construir().getTabela().length());
        assertEquals(59, base().tabela(comprimento200).construir().getTabela().length());

        assertEquals("Processo", base().tabela("   Processo   ").construir().getTabela(),
                "o trim() da LogPs precisa ser preservado");
        assertEquals("", base().tabela(null).construir().getTabela());
    }

    @Test
    @DisplayName("DATA ausente é derivada de HORA, no mesmo instante")
    void dataDerivadaDeHora() {
        Calendar c = Calendar.getInstance();
        c.set(2026, Calendar.JULY, 27, 23, 59, 59);
        c.set(Calendar.MILLISECOND, 900);
        Date hora = c.getTime();

        LogRegistro r = base().hora(hora).construir();

        assertEquals(hora.getTime(), r.getHora().getTime());
        assertEquals(hora.getTime(), r.getData().getTime(),
                "DATA precisa vir do mesmo instante de HORA; a LogPs chama new Date() duas "
                        + "vezes e pode gravar dias diferentes na virada da meia-noite");
    }

    @Test
    @DisplayName("Strings ausentes viram \"\" e numéricos ausentes viram null")
    void ausenciaSegueANulabilidadeDoDDL() {
        LogRegistro r = LogRegistro.novo().construir();

        // Colunas String não-Nullable na log_raw: '' representa ausência.
        assertEquals("", r.getIpComputador());
        assertEquals("", r.getTabela());
        assertEquals("", r.getValorAtual());
        assertEquals("", r.getValorNovo());

        // Colunas Nullable na log_raw.
        assertNull(r.getCodigoTemp());
        assertNull(r.getIdTabela());
        assertNull(r.getHash());
        assertNull(r.getQtdErrosDia());

        // Colunas NOT NULL numéricas: zero, nunca null.
        assertEquals(0L, r.getIdLog());
        assertEquals(0L, r.getIdLogTipo());
        assertEquals(0L, r.getIdUsu());
    }

    @Test
    @DisplayName("as Strings da LogDt são convertidas com tolerância")
    void conversaoTolerantePartindoDaLogDt() {
        // A LogDt guarda tudo como String e usa "" para ausente; "null" literal
        // também aparece (LogDtGen.setId_LogTipo trata esse caso).
        assertEquals(0L, base().idUsuario("").construir().getIdUsu());
        assertEquals(0L, base().idUsuario("   ").construir().getIdUsu());
        assertEquals(0L, base().idUsuario("null").construir().getIdUsu());
        assertEquals(12345L, base().idUsuario(" 12345 ").construir().getIdUsu());

        assertNull(base().idTabela("").construir().getIdTabela());
        assertNull(base().idTabela("null").construir().getIdTabela());
        assertEquals(Long.valueOf(98765L), base().idTabela("98765").construir().getIdTabela());

        // Valor não numérico é dado ruim vindo da origem, não motivo para
        // derrubar a gravação do log.
        assertNull(base().idTabela("abc").construir().getIdTabela());
        assertEquals(0L, base().idUsuario("abc").construir().getIdUsu());
    }

    @Test
    @DisplayName("os CLOBs passam intactos, sem sanitização")
    void clobsIntactos() {
        for (String payload : PayloadsReais.todos()) {
            LogRegistro r = base().valorAtual(payload).valorNovo(payload).construir();
            assertEquals(payload, r.getValorAtual(),
                    "VALOR_ATUAL foi alterado — a premissa da Solução 1 é preservar o formato");
            assertEquals(payload, r.getValorNovo());
        }
    }

    @Test
    @DisplayName("HASH precisa ter 32 caracteres, como o FixedString(32) da log_raw")
    void hashComTamanhoFixo() {
        String md5 = "d41d8cd98f00b204e9800998ecf8427e";
        assertEquals(md5, base().hash(md5).construir().getHash());
        assertNull(base().hash("").construir().getHash());
        assertNull(base().hash("   ").construir().getHash());

        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                base().hash("curto").construir();
            }
        });
    }

    @Test
    @DisplayName("comId e comIdLogTipo devolvem cópias, sem mutar o original")
    void copiasNaoMutamOOriginal() {
        LogRegistro original = base()
                .tabela("Processo")
                .valorNovo(PayloadsReais.JSON_COM_ORIGEM)
                .logTipoCodigo(28L)
                .construir();

        LogRegistro comId = original.comId(870123456789012345L);
        LogRegistro comTipo = comId.comIdLogTipo(44L);

        assertEquals(0L, original.getIdLog(), "o registro original não pode ter sido alterado");
        assertEquals(870123456789012345L, comId.getIdLog());
        assertEquals(0L, comId.getIdLogTipo());
        assertEquals(44L, comTipo.getIdLogTipo());
        assertEquals(870123456789012345L, comTipo.getIdLog(),
                "comIdLogTipo precisa preservar o ID_LOG já atribuído");

        // Os demais campos sobrevivem às duas cópias.
        assertEquals("Processo", comTipo.getTabela());
        assertEquals(PayloadsReais.JSON_COM_ORIGEM, comTipo.getValorNovo());
        assertEquals(28L, comTipo.getLogTipoCodigo());
        assertNotSame(original, comId);
    }

    @Test
    @DisplayName("as datas devolvidas são cópias defensivas")
    void datasSaoCopiasDefensivas() {
        Date hora = new Date();
        LogRegistro r = base().hora(hora).construir();

        r.getHora().setTime(0L);
        assertEquals(hora.getTime(), r.getHora().getTime(), "mutar o retorno não pode afetar o registro");

        hora.setTime(0L);
        assertTrue(r.getHora().getTime() != 0L, "mutar o argumento não pode afetar o registro");
    }

    private static LogRegistro.Builder base() {
        return LogRegistro.novo();
    }

    private static String repetir(char c, int vezes) {
        StringBuilder sb = new StringBuilder(vezes);
        for (int i = 0; i < vezes; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
