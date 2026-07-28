# log-writer

Biblioteca Java 8 que grava os logs de auditoria do Projudi no ClickHouse —
a **Solução 1** do TCC (offload de log).

Ela substitui **uma única coisa**: o `INSERT INTO PROJUDI.LOG` que a
`LogPs.inserir(LogDt)` executa hoje. Tudo o que vem antes disso — montar
`VALOR_ATUAL`/`VALOR_NOVO`, sortear o `CODIGO_TEMP`, truncar a `TABELA` — continua
exatamente igual, e o formato dos CLOBs é preservado byte a byte, sem parsing.

---

## Pendências de validação

> **`mvn test` verde NÃO significa integração validada.**
>
> Os 64 testes unitários rodam sem ClickHouse e sem Oracle de pé, por desenho
> (decisão 23). Eles provam o SQL, a ordem das colunas, o tipo de cada ligação
> de parâmetro e todo o comportamento de fila, lote e fallback — mas **nenhuma
> linha desta biblioteca jamais gravou num banco real**.
>
> A primeira tentativa de execução real, em 2026-07-28, cobrou isso na hora: o
> driver do ClickHouse nem carregava, por falta de `slf4j-api` no classpath
> (decisão 25). A conexão agora abre, mas a gravação continua sem prova.

A implementação foi feita numa máquina sem Docker (ver `docs/ambientes.md`,
seção 1). Os três itens abaixo continuam **sem execução** e são pré-requisito
para considerar a Frente B homologada — mesmo critério aplicado à Frente A, que
só foi dada como concluída depois de `make validate` real (decisão 16).

### 1. Teste de integração com o ClickHouse

Grava e lê de volta os três formatos reais de payload, exigindo igualdade byte a
byte em UTF-8. Roda com o ambiente reduzido, sem precisar do Oracle:

```bash
make up-lite                       # da raiz do repositório
cd log-writer
mvn test -Dclickhouse.integracao=true
```

> **Use `make up-lite`, não `make up`.** Numa máquina apertada, subir a pilha
> completa faz o ClickHouse dividir CPU e memória com Oracle, Kafka, Connect e
> Kafka UI. O resultado é `Read timed out` no meio dos lotes — e aí o teste
> mede a máquina, não o código. Numa execução de 8 GB com tudo no ar, a suíte
> levou 9 minutos e acusou perda de registros que nunca houve.
>
> Folgas disponíveis, se ainda apertar:
>
> | Propriedade | Padrão | Para quê |
> |---|---|---|
> | `-Dintegracao.registros` | `500` | volume do teste de fila |
> | `-Dclickhouse.url` | `jdbc:ch://localhost:8123/projudi_logs` | os tempos-limite sobem para 60 s automaticamente quando a URL não traz `?` |

**Esperado:** `Tests run: 68, Failures: 0, Errors: 0, Skipped: 0` — os 4 testes
hoje pulados passam a executar. Se continuarem em `Skipped`, a propriedade não
chegou ao surefire e nada foi testado.

**Já verificado nesta frente:** o driver carrega. O erro
`No suitable driver found for jdbc:ch://…` que aparecia aqui era falta de
`slf4j-api`, corrigida na decisão 25 e coberta por `ConexaoSupplierTest`.
O que continua sem prova é a **gravação**: ida e volta dos payloads, integridade
byte a byte e as 13 colunas.

> **Se falhar com `Connection reset`, não é o log-writer.** O teste agora sonda
> o `/ping` por socket cru antes de tocar no driver e imprime um diagnóstico
> com a causa provável e os comandos — em vez do rastro de 40 quadros do
> `SQLException`. Confirme com `curl http://localhost:8123/ping`: se o `curl`
> falhar igual, o problema é do ambiente.
>
> **Cuidado com o healthcheck:** o do compose roda `clickhouse-client` *dentro*
> do container, pelo protocolo nativo. Ele pode reportar `healthy` sem que a
> porta HTTP 8123 esteja utilizável a partir do host — que é o caminho que o
> log-writer usa. `make up-lite` terminar sem erro não prova que o JDBC
> conecta.

### 2. `OracleLogSink` contra um Oracle real

É o item menos coberto. O SQL e a ligação de parâmetros estão verificados por
teste unitário com `PreparedStatement` de proxy, mas o `TRUNCATE`, o
`setAutoCommit(false)` e o `commit` por lote só se provam em execução — e é
justamente esse sink que serve de **grupo de controle do benchmark**. Um erro
aqui contamina o número comparativo, não só o caminho de fallback.

Não há teste automatizado dedicado; a verificação sai da rodada do benchmark
com o Oracle disponível (item 3), que exercita os três pontos e confere a
completude ao final:

```bash
make up                            # precisa da pilha completa, não do up-lite
cd log-writer
mvn -q test-compile exec:java@bench -Dbench.n=2000 -Dbench.lotes=1,500
```

**Esperado:** na saída, `Oracle: disponível`, duas linhas `Oracle` na tabela de
resultado, e `Oracle … linhas … IDs distintos … OK` na conferência de
completude.

### 3. Benchmark no ambiente de referência

O número que vai para o relatório. **Tem que sair de um único ambiente**
(`docs/ambientes.md`, seções 3 e 5) — metade aqui e metade em outra máquina
produz resultados que não se combinam.

```bash
make up
cd log-writer
mvn -q test-compile exec:java@bench \
    -Dbench.n=20000 \
    -Dbench.lotes=1,100,500,2000 \
    -Dbench.warmup=2000 \
    -Dbench.repeticoes=5 \
    -Dbench.saida=../validacao/evidencias/bench-$(date +%Y%m%d).txt
```

Registre no relatório as especificações da máquina — o cabeçalho da saída já as
imprime — e versione o arquivo gerado em `validacao/evidencias/`.

### Fora desta lista, porque é fase posterior

A alteração na `LogPs` do `../projudi` está **documentada, não aplicada** (ver
"A alteração na LogPs", abaixo). A validação funcional com o Projudi rodando no
Eclipse é o teste **B.2** de `docs/ambientes.md`, seção 4, e depende do notebook
do TJ-GO.

---

## O que a leitura da LogPs estabeleceu

Antes de qualquer decisão de API, o código real
(`../projudi/src/br/gov/go/tj/projudi/ps/LogPs.java`) foi mapeado:

| O que a LogPs faz hoje | Onde | Como a biblioteca trata |
|---|---|---|
| Monta um INSERT **dinâmico**, só com as colunas não vazias | `LogPs.java:51-118` | INSERT **fixo** de 13 colunas; vazio vira `''` ou `NULL` conforme o DDL da `log_raw` |
| `executarInsert(sql, "ID_LOG", ps)` envolve tudo em `BEGIN … RETURNING ID_LOG INTO ?; END;` | `Persistencia.java:581-587` | `IdGerador` no cliente — o ID existe antes da gravação |
| `ID_LOG_TIPO` pode vir de subselect em `PROJUDI.LOG_TIPO` | `LogPs.java:71-75` | `LogTipoResolver` contra a dimensão `log_tipo` **do ClickHouse**, com cache |
| `TABELA` truncada em 59 se passar de 60 | `LogPs.java:56-59` | mesma regra, no `LogRegistro.Builder` |
| `CODIGO_TEMP` aleatório 0..100000 | `LogPs.java:48` | continua na LogPs; a biblioteca só transporta |
| CLOBs ligados com `setString`, sem sanitização | `Persistencia.java:644-646` | idem — é o que garante fidelidade |
| A conexão é a da transação de negócio (`FabricaConexao.PERSISTENCIA`) | `LogNe.java:200-213` | **muda**: ver "Diferença semântica" abaixo |

---

## Instalação no Projudi

```xml
<dependency>
    <groupId>br.jus.tjgo.projudi</groupId>
    <artifactId>log-writer</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Traz junto apenas o `clickhouse-jdbc:0.7.2` com classificador `all` (uber jar
sombreado, sem transitivas). Sem Spring. O log **desta biblioteca** usa
`java.util.logging`, que já está no JDK.

> **`slf4j-api` precisa estar no classpath**, mesmo sem nenhuma classe nossa
> importá-la: o uber jar do ClickHouse não a empacota e o `<clinit>` do driver
> depende dela. No Projudi isso já está resolvido — `slf4j-api:1.7.25` está em
> `WEB-INF/lib`. Por isso a dependência é declarada em escopo `provided`: ela
> não é exportada para o WAR e não arrisca uma segunda versão no classpath.
> Ver decisão 25.

### A alteração na LogPs

Extraia o corpo atual de `inserir(LogDt)` para um método privado
`inserirNoOracle(LogDt)` — **renomeação pura, sem tocar numa linha do conteúdo**
— e deixe `inserir` assim:

```java
public void inserir(LogDt dados) throws Exception {
    dados.setCodigoTemp(String.valueOf(Math.round(Math.random() * 100000)));

    LogWriter writer = LogWriter.instancia();
    if (writer.ativo()) {
        long idLog = writer.inserir(
            LogRegistro.novo()
                .tabela(dados.getTabela())
                .idTabela(dados.getId_Tabela())
                .idLogTipo(dados.getId_LogTipo())
                .logTipoCodigo(dados.getLogTipoCodigo())
                .idUsuario(dados.getId_Usuario())
                .ipComputador(dados.getIpComputador())
                .valorAtual(dados.getValorAtual())
                .valorNovo(dados.getValorNovo())
                .codigoTemp(dados.getCodigoTemp())
                .construir());
        dados.setId(String.valueOf(idLog));
        if (!writer.destino().gravaNoOracle()) {
            return;
        }
    }
    inserirNoOracle(dados);
}
```

São ~14 linhas adicionadas e uma extração de método. Reverter é apagar o bloco
e re-inlinar — a razão de o desenho ter ficado assim.

O `inserirErro(LogErroDt)` recebe o mesmo tratamento, acrescentando
`.hash(dados.getHash())` e `.qtdErrosDia(dados.getQtdErrosDia())`.

### Ciclo de vida

```java
public class LogWriterListener implements ServletContextListener {
    @Override public void contextInitialized(ServletContextEvent e) {
        LogWriter.configurar(LogWriterConfig.doAmbiente(), new SinkOracleLegado());
    }
    @Override public void contextDestroyed(ServletContextEvent e) {
        LogWriter.encerrar();   // drena a fila; é o que fecha a janela de perda
    }
}
```

Não há shutdown hook automático de propósito: um hook segurando referência ao
sink impede a coleta do classloader da aplicação no undeploy do Tomcat.

---

## Configuração

System property, ou variável de ambiente equivalente em maiúsculas com `_`.

| System property | Padrão | Para quê |
|---|---|---|
| `projudi.logwriter.destino` | `ORACLE` | `ORACLE` · `CLICKHOUSE` · `AMBOS` |
| `projudi.logwriter.clickhouse.url` | `jdbc:ch://localhost:8123/projudi_logs` | |
| `projudi.logwriter.clickhouse.usuario` | `projudi_app` | |
| `projudi.logwriter.clickhouse.senha` | *(vazio)* | vem do `.env` |
| `projudi.logwriter.lote.max` | `500` | `1` = síncrono |
| `projudi.logwriter.lote.intervaloMs` | `1000` | idade máxima na fila |
| `projudi.logwriter.fila.capacidade` | `10000` | teto da fila |
| `projudi.logwriter.tentativas` | `2` | reenvios antes do fallback |
| `projudi.logwriter.workerId` | derivado | **configure por instância** |

**O padrão de `destino` é `ORACLE`.** Subir o jar no classpath sem configurar
nada não muda comportamento nenhum.

> **`workerId` merece atenção.** Sem ele, o valor é derivado de hostname+PID e um
> aviso vai para o log. Com 1024 slots e várias JVMs do Projudi contra o mesmo
> destino, a colisão não é impossível. Em produção, atribua explicitamente.

### Os três estados da flag

```
ORACLE      → a biblioteca fica inerte; o Projudi grava como sempre gravou
CLICKHOUSE  → só ClickHouse; o Oracle vira fallback de exceção
AMBOS       → escrita dupla, para o período de sombra em homologação
```

No modo `AMBOS`, **a cópia no Oracle é feita pela própria LogPs**, no código que
ela já executa hoje e dentro da transação de negócio. A biblioteca só cuida do
ClickHouse. Assim o modo sombra compara o ClickHouse contra o comportamento real
de produção, e não contra uma reimplementação dele.

Para que a comparação feche **por chave**, o INSERT legado precisa passar a
incluir `ID_LOG` com o valor devolvido por `writer.inserir(...)`. Isso é seguro:
a trigger `LOG_ID_LOG_TRG` (`BancoDeDados/07_CreateTrigger.sql`) só atribui valor
`IF INSERTING AND :new.Id_Log IS NULL`, então um ID preenchido não é
sobrescrito nem consome `LOG_ID_LOG_SEQ.NEXTVAL` — a numeração legada segue
intacta.

---

## Comportamento em falha

Registrado em `docs/decisoes.md`, decisão 19. O resumo operacional:

| Situação | O que acontece |
|---|---|
| ClickHouse indisponível | o lote é reenviado `tentativas` vezes e depois vai para o fallback (Oracle) |
| Fila cheia | o registro vai direto ao fallback, na thread chamadora — mesmo custo que hoje |
| Fallback também fora | contabilizado em `PERDIDOS` e registrado com nível `SEVERE` |
| Kill -9 / OOM | perde o que estiver em memória — **janela de até `fila.capacidade + lote.max` registros** (padrão: 10.500), ou o que entrou nos últimos `lote.intervaloMs` |
| `encerrar()` chamado | drena a fila; a janela fecha |

`escrever` **nunca lança e nunca bloqueia**. Gravar log não pode derrubar uma
operação de negócio.

### Observabilidade

```java
Metricas m = LogWriter.instancia().metricas();
log.info(m.resumo());
// log-writer[recebidos=… destino=… fallback=… (saturacao=… falha=…) … PERDIDOS=0]
```

Durante a transição, **`fallback` é a métrica que importa**: ela diz quantos logs
foram pelo caminho velho, e sem ela um ClickHouse intermitente vira um desvio
silencioso que invalida o número do relatório. Há também um logger dedicado,
`projudi.logwriter.FALLBACK`, para roteá-la sem filtrar texto.

### Diferença semântica de transação

Hoje o log grava na mesma transação da operação de negócio: rollback do negócio
desfaz o log junto. Com a fila, a gravação sai daquela transação, então **um log
pode chegar ao ClickHouse mesmo que a operação de negócio sofra rollback
depois**. É uma limitação conhecida, aceita no MVP e registrada em
`docs/decisoes.md`; a mitigação futura é chamar o writer só após o commit.

---

## ID_LOG

Gerado no cliente, sem ida a banco nenhum, em 64 bits:

```
(millisDesde2020 << 22) | (workerId << 12) | sequencia
     41 bits                10 bits          12 bits
```

Cabe em `UInt64` e em `NUMBER(24)`, é monotônico (preserva o desempate do
`ORDER BY (HORA, ID_USU, ID_LOG)`) e cai numa faixa ordens de grandeza acima da
numeração legada — `LOG_ID_LOG_SEQ` está em 1,05×10⁸, os IDs gerados ficam na
casa de 8,7×10¹⁷. Nenhuma colisão possível na migração.

Decodificação, útil na conferência:

```java
IdGerador.instanteDe(id);   // epoch ms
IdGerador.workerDe(id);     // 0..1023
IdGerador.sequenciaDe(id);  // 0..4095
```

---

## Testes

```bash
mvn test
```

64 testes unitários, **sem depender de ClickHouse nem de Oracle de pé** — e,
pela mesma razão, sem provar nada sobre a gravação real (ver "Pendências de
validação"). A
costura que permite isso é o `ConexaoSupplier`: o `JdbcFalso` (em
`src/test/.../apoio/`) monta `Connection` e `PreparedStatement` com
`java.lang.reflect.Proxy` e registra cada `setXxx`, então o SQL e a ligação de
parâmetros são conferidos coluna a coluna sem banco.

### Teste de integração real

**Ainda não executado** — ver "Pendências de validação", item 1.

Pulado por padrão; roda com o ambiente de pé:

```bash
make up-lite                       # da raiz do repositório
cd log-writer
mvn test -Dclickhouse.integracao=true
```

Grava e lê de volta os **três formatos reais** de payload — `[campo:valor;…]`,
JSON com sufixo `[Origem:]`, e texto livre — mais um caso-limite com aspas,
barras, quebras de linha e tabs, exigindo igualdade **byte a byte em UTF-8** (não
só `String.equals`, que passaria com uma normalização Unicode no meio do
caminho). A origem no Projudi é Latin-1; é aí que uma conversão errada
apareceria.

Credenciais: `-Dclickhouse.usuario=… -Dclickhouse.senha=…` (padrão: o
`projudi_app` do `.env.example`).

---

## Benchmark

Escreve N registros no **Oracle** e no **ClickHouse** do compose, no mesmo host
e na mesma janela de tempo, e cronometra os dois.

**Ainda não executado contra bancos reais** — ver "Pendências de validação",
itens 2 e 3. O que já rodou foi apenas o modo seco (`bench.seco=true`), que
exercita a invocação e o formato da saída sem tocar em banco nenhum.

```bash
make up            # da raiz — precisa do Oracle, que é o grupo de controle
cd log-writer
mvn -q test-compile exec:java@bench \
    -Dbench.n=20000 \
    -Dbench.lotes=1,100,500,2000 \
    -Dbench.warmup=2000 \
    -Dbench.repeticoes=5 \
    -Dbench.saida=../validacao/evidencias/bench-$(date +%Y%m%d).txt
```

| Parâmetro | Padrão | |
|---|---|---|
| `bench.n` | `10000` | registros medidos por rodada |
| `bench.warmup` | `1000` | aquecimento, fora do cronômetro |
| `bench.repeticoes` | `3` | rodadas por configuração; a mediana é reportada |
| `bench.lotes` | `1,100,500,2000` | `1` = síncrono, comparável ao que a LogPs faz hoje |
| `bench.limpar` | `true` | esvazia as duas tabelas antes de medir |
| `bench.oracle` | `true` | inclui o grupo de controle |
| `bench.clickhouse` | `true` | |
| `bench.seco` | `false` | grava em memória, sem banco — **não é medição** |
| `bench.saida` | — | espelha o relatório num arquivo UTF-8 |

Credenciais saem do `.env` da raiz (`CH_APP_USER`, `CH_APP_PASSWORD`,
`ORACLE_PROJUDI_PASSWORD`) e podem ser sobrescritas por
`-Dbench.ch.usuario=…` etc. O cabeçalho da saída imprime a procedência de cada
parâmetro, justamente para que a rodada seja reproduzível a partir do relatório.

### Metodologia — o que o harness garante

- **Não depende do Projudi.** Nem Eclipse, nem Tomcat, nem a base do TJ-GO.
- **Não depende de estado local.** Cada execução gera a própria faixa de IDs.
- **Mesmo payload nos dois destinos**, construído antes de qualquer cronômetro.
- **Warmup separado**, para não medir JIT frio nem abertura de conexão.
- **Repetições com mediana** — uma rodada única é anedota, não medição.
- **Conferência de completude** ao final: linhas × `ID_LOG` distintos nos dois
  lados.

### O que o número NÃO diz

O harness mede o **caminho de escrita de um cliente**. Ele não mede o ganho real
da Solução 1, que é tirar carga de redo, buffer cache e I/O do banco
transacional que atende o usuário — isso só aparece em medição de produção. Essa
ressalva sai impressa no rodapé de toda execução, para não se perder no
caminho até o relatório.

> **Os números finais têm que sair de um único ambiente de referência.**
> Ver `docs/ambientes.md`, seções 3 e 5. O `make up-lite` **não serve**: sem o
> Oracle local não há grupo de controle.

### Saída em Windows

O console do Windows roda em Cp1252 e exibe a acentuação incorretamente, mesmo
com o conteúdo certo. Use `-Dbench.saida=<arquivo>`: o arquivo sai em UTF-8,
colável direto no relatório.

---

## Mapa do código

```
LogSink                 fronteira que substitui o INSERT da LogPs
├── ClickHouseLogSink   destino: projudi_logs.log_raw
├── OracleLogSink       grupo de controle do benchmark e fallback do laboratório
├── BufferedLogSink     fila + lote + fallback — o sink que a LogPs enxerga
├── CompositeLogSink    escrita dupla (modo sombra)
└── MemoriaLogSink      abstração testável, e smoke test dentro do Projudi

LogWriter               fachada; ponto de entrada único
LogRegistro             as 13 colunas, imutável, Builder que fala a língua da LogDt
LogDestino              a feature flag
LogWriterConfig         system property / variável de ambiente
IdGerador               ID_LOG de 64 bits, sem ida a banco
Metricas                contadores, incluindo o desvio ao fallback
LogTipoResolver         LOG_TIPO_CODIGO → ID_LOG_TIPO, contra a dimensão do ClickHouse
ConexaoSupplier         a costura que torna os sinks testáveis sem banco
```
