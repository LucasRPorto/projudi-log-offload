# Registro de decisões técnicas

Decisões tomadas durante a construção da infraestrutura (Frente A) que **não**
estavam definidas no escopo, e por quê. Formato: decisão → alternativas
consideradas → motivo.

Sessão de 2026-07-21.

---

## 1. Versões de imagem

| Serviço | Versão fixada | Motivo |
|---|---|---|
| ClickHouse | `clickhouse/clickhouse-server:25.8` | Linha LTS. Havia versões 26.x disponíveis, mas para um trabalho que vai ser defendido meses depois interessa a versão com suporte longo, não a mais nova. |
| Oracle | `gvenzl/oracle-free:23-slim-faststart` | `slim` corta os componentes que o MVP não usa (APEX, exemplos). `faststart` traz o banco já criado na imagem: o primeiro start cai de ~15 min para ~2-3 min, e o ciclo `make reset` deixa de ser proibitivo. |
| Kafka | `confluentinc/cp-kafka:8.0.6` | Ver decisão 2. |
| Kafka Connect | `quay.io/debezium/connect:3.6.0.Final` | Última `.Final` da linha 3.6, contemporânea do Kafka 4.x que a CP 8.0 embarca. |
| Kafka UI | `kafbat/kafka-ui:v1.5.0` | Ver decisão 3. |
| Driver JDBC Oracle | `ojdbc11:23.8.0.25.04` | Ver decisão 4. |

Todas as versões são parametrizadas no `.env` — trocar qualquer uma é editar uma
linha, não caçar string no compose.

---

## 2. Kafka: `confluentinc/cp-kafka` em vez de `apache/kafka`

O escopo permitia qualquer um dos dois. Escolhido o da Confluent por um motivo
prático: a imagem `apache/kafka` não declara `/var/lib/kafka/data` no
`Dockerfile`, então um volume nomeado montado ali é criado pertencendo ao
`root`, enquanto o broker roda como uid 1000 — e o start falha com erro de
permissão. Contornar isso exigiria rodar o broker como root ou fazer malabarismo
de `chown` no entrypoint.

A imagem `cp-kafka` já cria o diretório com o dono correto. Ambas rodam KRaft
puro (sem ZooKeeper), que era o requisito real.

---

## 3. Kafka UI: `kafbat/kafka-ui` em vez de `provectuslabs/kafka-ui`

O escopo indicava a imagem da Provectus. O projeto foi descontinuado — a última
release é a `v0.7.2`, de 2023, com Kafka clients 3.5. O `kafbat/kafka-ui` é o
fork mantido pelos mesmos desenvolvedores, com suporte aos brokers Kafka 4.x que
este ambiente usa. Mesma interface, mesmas variáveis de configuração.

É um serviço opcional: se ele falhar, nada mais no ambiente é afetado, e
`make validate` não o considera.

---

## 4. Driver JDBC da Oracle: baixado no build da imagem

O conector Oracle do Debezium precisa do `ojdbc` no classpath e a imagem oficial
não o distribui.

**Escolhido:** um `Dockerfile` derivado (`infra/debezium/Dockerfile`) que baixa o
jar do Maven Central e o instala em `/kafka/libs/`.

Alternativas descartadas:

- *Bind mount de um jar baixado à mão.* Se o arquivo não existir no host, o
  Docker cria um **diretório** com aquele nome, e o erro resultante não tem
  relação óbvia com a causa. Além disso obrigaria um passo manual antes do
  primeiro `make up`.
- *Colocar o jar no diretório do plugin* (`/kafka/connect/debezium-connector-oracle/`).
  Montar um volume ali esconderia os jars do próprio conector.

`/kafka/libs` é o classpath do sistema: o classloader isolado de plugin do
Connect delega classes de driver JDBC ao classloader pai, então o driver fica
visível para o conector Oracle e para qualquer outro que venha a precisar.

**Sobre a licença:** o `ojdbc11` é publicado pela própria Oracle no Maven Central
sob a *Oracle Free Use Terms and Conditions* (FUTC), que permite download e uso
sem custo e sem conta. É o mesmo motivo pelo qual a imagem de banco usada aqui é
a `gvenzl/oracle-free` e não a oficial da Oracle (essa sim exige login).

Se a rede bloquear o `repo1.maven.org`, o `setup.sh` falha com mensagem
explícita e o README traz o procedimento manual.

---

## 5. ARCHIVELOG

O Debezium Oracle Connector usa LogMiner, que **exige** o banco em modo
ARCHIVELOG. A imagem `gvenzl/oracle-free` sobe em NOARCHIVELOG por padrão.

> **Esta decisão foi corrigida depois da primeira execução real.** A versão
> original dizia para usar as variáveis de ambiente `ENABLE_ARCHIVELOG=true` e
> `ENABLE_FORCE_LOGGING=true` no compose. **Isso não funciona:** essas variáveis
> não existem no entrypoint do `gvenzl/oracle-free`. O `make validate` de
> 2026-07-21 acusou `log_mode = NOARCHIVELOG` com as variáveis definidas, e a
> inspeção do código da imagem confirmou que elas nunca são lidas.
>
> Fica registrado como erro em vez de ser apagado: é exatamente o tipo de
> suposição que só cai em execução, e o histórico da correção vale mais que uma
> decisão que finge ter nascido certa.

**Escolhido:** a sequência explícita, em
`infra/oracle/init/sql/05_enable_archivelog.sql`:

```sql
SHUTDOWN IMMEDIATE;
STARTUP MOUNT;
ALTER DATABASE ARCHIVELOG;
ALTER DATABASE OPEN;
ALTER DATABASE FORCE LOGGING;
ALTER PLUGGABLE DATABASE ALL OPEN;
ALTER PLUGGABLE DATABASE ALL SAVE STATE;
```

Três detalhes que não são óbvios:

- **Precisa de conexão local** (`sqlplus / as sysdba` dentro do container).
  `STARTUP` não funciona através do listener.
- **A decisão de executar fica no shell**, não no SQL: `SHUTDOWN` e `STARTUP`
  são comandos do SQL*Plus e não podem ser condicionados dentro de um bloco
  PL/SQL. O orquestrador consulta `v$database.log_mode` e só chama o script se
  precisar.
- **Depois do restart o listener leva alguns segundos** para registrar de novo
  o serviço do PDB. Sem esperar, o script seguinte falharia com ORA-12514 e o
  sintoma não apontaria para a causa. Há um laço de espera nos dois pontos que
  executam a sequência.

Aplicado em dois lugares, com o mesmo `.sql`:

| Onde | Quando |
|---|---|
| `infra/oracle/init/01_init_projudi.sh` | ambientes novos, no primeiro start |
| `scripts/enable-archivelog.sh` (`make archivelog`) | ambiente já de pé, sem `make reset` |

O segundo existe para não obrigar a destruir os volumes e esperar minutos só
para trocar o `log_mode`.

**Não falha em silêncio:** se a sequência não funcionar, o init imprime um bloco
de aviso no log do container, e o item `f` do `make validate` marca o problema.
A Solução 1 não depende de archivelog — só a Solução 2 fica bloqueada.

**Consequência operacional:** com ARCHIVELOG ligado, o Oracle acumula archived
logs até encher a FRA. Num ambiente de laboratório de vida curta isso não
incomoda; se o ambiente ficar semanas de pé, é preciso limpar
(`RMAN> DELETE ARCHIVELOG ALL;`) ou aumentar `db_recovery_file_dest_size`.

---

## 6. Supplemental logging: `(ALL) COLUMNS` na `PROC`

Sem supplemental logging, o redo guarda apenas o ROWID e as colunas alteradas —
um `UPDATE` de uma coluna produziria um evento com as outras 42 nulas, e o
histórico da Solução 2 seria inútil.

Aplicado em dois níveis, como a documentação do Debezium pede:

- `ALTER DATABASE ADD SUPPLEMENTAL LOG DATA` no `CDB$ROOT` (mínimo, nível banco);
- `ALTER TABLE PROJUDI.PROC ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS` no PDB.

O custo é redo maior a cada `UPDATE` na `PROC` — e é justamente um dos números
que a Frente C deve medir, porque em produção esse custo recai sobre o banco
transacional que a Solução 2 pretende aliviar. Aplicado a **uma** tabela apenas.

---

## 7. Formato do Kafka engine: `JSONAsString`, não `JSONEachRow`

O escopo deixava a escolha em aberto. **Escolhido `JSONAsString` + `JSONExtract`
na MATERIALIZED VIEW.**

Com `JSONEachRow` seria preciso declarar `before` e `after` como `Tuple` de 43
campos cada, dentro da definição da tabela Kafka. O problema não é a verbosidade:
é que qualquer mensagem que não case exatamente com esse tipo faz o ClickHouse
descartar o **bloco inteiro** e avançar o offset — perda silenciosa de eventos.
Uma coluna nova na `PROC`, um campo ausente, e o histórico ganha um buraco que
ninguém percebe.

Com `JSONAsString` a mensagem entra como texto opaco numa coluna `String` e o
desmembramento acontece na MV, campo a campo. Campo ausente vira `NULL` naquela
coluna, não erro.

Custo: reparsear o JSON na MV. Mitigado pelo fato de o ClickHouse deduplicar
subexpressões idênticas no DAG de execução — `JSONExtractRaw(env, 'after')`
aparece 43 vezes no SQL e é avaliado uma vez por linha.

`kafka_handle_error_mode = 'stream'` completa a proteção: mensagem ilegível vai
para as colunas virtuais `_error` / `_raw_message` em vez de derrubar o consumo.

**A MV aceita os dois formatos de envelope.** Com `schemas.enable=false` (o
padrão configurado aqui) o JSON já é o payload; com `schemas.enable=true` ele vem
dentro de `"payload"`. O alias `env` da MV resolve os dois casos, então a Frente C
pode ligar os schemas sem mexer no DDL.

---

## 8. `decimal.handling.mode=double` e `time.precision.mode=connect`

Duas configurações do conector que existem para simplificar a MV:

**`decimal.handling.mode=double`** — o padrão (`precise`) serializa `NUMBER` como
bytes de `BigDecimal` codificados em base64, o que exigiria decodificação binária
dentro do ClickHouse. Com `double`, os valores chegam como número JSON.

Ressalva registrada: `double` tem 53 bits de mantissa (~9×10¹⁵ exato). As colunas
`NUMBER(24)` da `PROC` (`ID_PROC`, `PROC_NUMERO`, …) vêm de sequences e hoje
estão na casa de 10⁹–10¹⁰, com folga de cinco ordens de grandeza. Os valores
monetários `NUMBER(20,2)` são convertidos para `Decimal(20,2)` no destino, então
o armazenamento final é exato — o `double` é só o formato de transporte. A
alternativa (`string`) tornaria o parsing heterogêneo, porque `NUMBER(p,0)` com
p ≤ 18 continuaria chegando como inteiro.

**`time.precision.mode=connect`** — força todos os temporais a epoch em
**milissegundos**. Com o padrão `adaptive`, a unidade varia conforme o tipo da
coluna de origem, e a MV precisaria de uma conversão diferente por coluna. Aqui
todas as 7 colunas `DATE` usam a mesma expressão.

**`log.mining.strategy=online_catalog`** — usa o dicionário de dados corrente em
vez de gravar o dicionário no redo. Muito mais leve, com a limitação de não
acompanhar DDL da tabela capturada. Como a estrutura da `PROC` é fixa no MVP, é
a escolha certa; se a Frente C precisar testar evolução de schema, trocar para
`redo_log_catalog`.

---

## 9. Mapeamento `NUMBER(24) → UInt64`

O escopo pedia `UInt64` com justificativa, ou `Int64` justificado.

Mantido `UInt64`, com a ressalva registrada aqui e no próprio DDL:
**`NUMBER(24)` formalmente excede `UInt64`** (10²⁴−1 contra ~1,84×10¹⁹). Não é um
mapeamento exato.

Foi escolhido assim porque todos os `NUMBER(24)` do schema são identificadores
gerados por sequence, hoje na casa de 10⁹–10¹⁰; o teto de `UInt64` só seria
atingido com ~10¹⁹ registros. As alternativas exatas — `Decimal(24,0)` (16 bytes,
aritmética mais lenta) ou `String` — custariam o dobro de espaço e tirariam
`ID_LOG` / `ID_PROC` da comparação nativa que a chave de ordenação usa.

`UInt` e não `Int` porque são identificadores: nunca negativos, e o tipo sem
sinal documenta essa invariante.

Regra geral aplicada às demais colunas: `NUMBER(p)` → menor inteiro sem sinal que
cobre 10^p − 1. Daí `NUMBER(10) → UInt64` (não cabe em `UInt32`),
`NUMBER(5) → UInt32`, `NUMBER(3) → UInt16`, `NUMBER(1) → UInt8`.

---

## 10. Nulabilidade assimétrica entre `log_raw` e `proc_cdc`

Em `log_raw`, colunas não-`Nullable` sempre que possível: o produtor é a nossa
própria classe Java, que controla o que envia, e `HORA` é a primeira coluna da
chave de ordenação (chave `Nullable` degrada o índice esparso).

Em `proc_cdc`, todas as 43 colunas de negócio são `Nullable` exceto `ID_PROC`. O
produtor ali é o LogMiner, e um evento com coluna ausente — supplemental logging
incompleto, DDL, before-image parcial — faria a MV abortar o bloco inteiro se a
coluna fosse `NOT NULL`. Preferiu-se ingerir com `NULL` e detectar depois a não
ingerir nada.

---

## 11. Usuário de aplicação do ClickHouse criado via SQL

O escopo pedia "usuário de aplicação além do default".

O caminho óbvio seria a variável `CLICKHOUSE_USER` do entrypoint oficial — mas
ela **remove** o usuário `default`, o que contraria o "além do default" e tira a
conveniência do `make ch` sem senha.

**Escolhido:** um arquivo estático em `users.d/` que só habilita
`access_management` para o `default`, e um script `ddl/90_app_user.sh` que cria o
usuário de aplicação via `CREATE USER`, com a senha vinda do `.env`. O arquivo
versionado não carrega segredo, e a senha fica armazenada como hash sha256 no
diretório `access/` do volume.

O script roda por último no `initdb.d` porque os `GRANT` dependem dos bancos já
existirem. Ele deliberadamente **não** usa `set -e` nem `exit`: o entrypoint do
ClickHouse executa `.sh` sem bit de execução via `source`, e um `exit` mataria o
entrypoint inteiro sem mensagem útil.

---

## 12. Init do Oracle: um orquestrador `.sh` chamando `.sql` parametrizados

Dois problemas resolvidos de uma vez:

1. **Senhas.** `PROJUDI` e `c##dbzuser` precisam de senha vinda do `.env`. Um
   `.sql` versionado não lê variável de ambiente; o orquestrador passa as senhas
   como parâmetros posicionais do SQL*Plus (`&1`, `&2`).

2. **Container.** É um banco multitenant. Parte do setup é no `CDB$ROOT` (usuário
   comum, supplemental logging de banco) e parte no PDB (schema, tabelas,
   supplemental logging da tabela). Um `.sql` solto herdaria o container em que a
   imagem resolveu conectar; aqui cada script recebe uma connect string
   **explícita**.

Os `.sql` ficam em `infra/oracle/init/sql/` — subdiretório, para que a imagem não
os execute por conta própria (ela varre apenas o primeiro nível).

Os grants nas views `V_$` dinâmicas são concedidos **um a um**, dentro de um
bloco PL/SQL com tratamento de exceção: a lista exata varia entre versões do
Oracle e do Debezium, e uma view inexistente abortaria o init inteiro. O que
falhar vira aviso visível no log do container — sem mascarar o problema.

---

## 13. Acréscimos ao schema além do especificado

Três itens que não estavam no escopo:

- **`projudi_logs.log_tipo` no ClickHouse.** Sem a dimensão, toda consulta de
  validação precisaria decorar códigos numéricos. `ReplacingMergeTree` porque a
  tabela é recarregada por inteiro, não incrementada.
- **Sequences `PROJUDI.SEQ_LOG` e `PROJUDI.SEQ_PROC` no Oracle.** Não existem em
  produção (lá os IDs vêm da aplicação), mas permitem que as Frentes B e C gerem
  carga de teste sem administrar IDs à mão.
- **Índices na `PROJUDI.LOG`** (`HORA`; `ID_USU, HORA`; `TABELA, ID_TABELA`).
  Espelham os padrões de consulta que a chave de ordenação do ClickHouse atende.
  Sem eles, a comparação de tempo de resposta Oracle × ClickHouse seria desonesta
  — estaria medindo full table scan contra índice.

---

## 14. `log-writer`: Java 8 e clickhouse-jdbc 0.7.2

`java.version` = 8 confirmado no `pom.xml` do repositório `../projudi`. O
artefato precisa rodar dentro daquela aplicação, então compilar para uma versão
mais nova não é opção.

Isso fixa o driver: **`clickhouse-jdbc` 0.7.2 é a última linha compatível com
Java 8** (0.8.x em diante exige JDK mais recente).

Classificador `all` (uber jar sombreado) com exclusão de todas as transitivas: o
classpath do Projudi é grande e antigo, e o risco de conflito de versão de
biblioteca comum (HTTP client, JSON) é real. O jar sombreado elimina a classe
inteira de problema.

> **Corrigido em 2026-07-28, na primeira execução real.** A exclusão de *todas*
> as transitivas está certa no motivo e era larga demais no alcance: o uber jar
> `all` não empacota o `org.slf4j`, e sem ele o `<clinit>` do
> `ClickHouseDriver` morre com `NoClassDefFoundError` — que o `DriverManager`
> engole, devolvendo `No suitable driver found`. Foi acrescentado
> `org.slf4j:slf4j-api` em escopo `provided`. Ver decisão 25.

---

## 15. `.env` na raiz, compose em `infra/`

O Docker Compose procura o `.env` no diretório do arquivo de compose, não no
diretório de onde é invocado. Com o compose em `infra/`, um `.env` na raiz seria
ignorado silenciosamente.

Todos os pontos de entrada (Makefile e os três scripts) passam
`--env-file .env -f infra/docker-compose.yml` explicitamente. Quem rodar
`docker compose` à mão de dentro de `infra/` **não** vai enxergar as variáveis —
está documentado no topo do `.env.example` e do compose.

---

## 16. Pendências de validação

O ambiente foi construído numa máquina Windows **sem Docker e sem possibilidade
de instalá-lo**: conta de domínio corporativo sem privilégio administrativo, com
WSL2, VirtualMachinePlatform, HypervisorPlatform e Hyper-V desabilitados na
imagem do sistema. Não foi possível executar `setup.sh`, `make up` nem
`make validate` na sessão em que o código foi escrito.

A primeira execução real cabe ao ambiente de referência do projeto — ver
`docs/ambientes.md`, seção 6. **Enquanto `make validate` não terminar com
`0 falharam` numa execução real, considere a Frente A como não homologada.**

O que **foi** verificado estaticamente:

- sintaxe de todos os scripts `.sh` (`bash -n`) e do `Makefile`;
- YAML do compose e XML das configs do ClickHouse bem formados;
- `pom.xml` válido e as versões de artefato existentes no Maven Central
  (`ojdbc11:23.8.0.25.04`, `clickhouse-jdbc:0.7.2`);
- existência de todas as tags de imagem nos respectivos registries;
- contagem de colunas: 43 na `PROC` (Oracle e ClickHouse), 47 na `proc_cdc`
  (43 + 4 de metadata), 13 na `log_raw`;
- portas 1521, 8080, 8083, 8123, 9000, 9092 e 29092 livres na máquina.

### Estado da validação

**Frente A homologada em 2026-07-21.** `make validate` num ambiente criado do
zero (`make reset && make up`):

```
32 passaram   0 falharam   0 avisos
```

Executado numa máquina Linux (WSL) com 8 GB de RAM no host e apenas 3 GB
disponíveis ao daemon Docker — abaixo do mínimo recomendado de 12 GB, e ainda
assim suficiente para a pilha completa. O aviso do `setup.sh` continua correto
como recomendação, mas o piso real é mais baixo que o documentado.

Confirmado em execução, item a item:

| Área | Resultado |
|---|---|
| `setup.sh` completo, incluindo o build da imagem do Connect com o `ojdbc11` baixado do Maven Central | ✅ |
| Os 6 DDLs do ClickHouse aplicados no primeiro start | ✅ |
| `proc_cdc_mv` — a MATERIALIZED VIEW com `JSONExtract`, `accurateCastOrNull` e `toDateTime64` sobre `Nullable` | ✅ |
| `proc_cdc` com exatamente 47 colunas | ✅ |
| Usuário `projudi_app` criado e com ciclo completo de escrita e leitura em `log_raw` | ✅ |
| Oracle: schema `PROJUDI`, as 43 colunas da `PROC`, seed nas 3 tabelas | ✅ |
| **Todos os datafiles dentro do volume `oracle-data`** (decisão 18) | ✅ |
| Kafka respondendo, tópicos internos do Connect criados | ✅ |
| Kafka Connect com o plugin `OracleConnector` e o `ojdbc11` no classpath | ✅ |
| **Oracle em ARCHIVELOG, aplicado pelo init** (decisão 5) | ✅ |
| Supplemental logging de banco e `(ALL) COLUMNS` na `PROJUDI.PROC` | ✅ |
| Usuário `c##dbzuser` existente | ✅ |

### O que a validação encontrou pelo caminho

Quatro suposições caíram apenas em execução real, nenhuma detectada pela
verificação estática:

1. **`ENABLE_ARCHIVELOG` seria interpretada pela imagem** — não é (decisão 5).
2. **`healthy` do Docker significaria "pronto para consulta"** — não significa;
   consultas na janela de carregamento de metadados voltavam vazias e o
   relatório chegou a imprimir "banco X NÃO existe" duas linhas acima de
   "tabela de X existe".
3. **Silenciar a saída de um comando seria inócuo** — esconde prompts
   interativos e trava o script indefinidamente.
4. **Nome de datafile relativo seria equivalente a absoluto** — não é; o
   arquivo vai para a camada gravável da imagem e o banco morre na primeira
   recriação de container (decisão 18).

A quarta é a mais instrutiva: sobreviveu à revisão estática **e** a uma execução
bem-sucedida de 30 itens, porque só se manifesta num evento de ciclo de vida —
recriação de container — que nenhum teste anterior havia exercitado.

### O que continua sem execução

| Item | Escopo |
|---|---|
| `scripts/enable-archivelog.sh` (`make archivelog`) | Nunca rodou com sucesso: o caminho validado foi o do init, num ambiente novo. Convenência para ambientes antigos; sem uso previsto agora. |
| `scripts/register-connector.sh` | Primeira tarefa da Frente C. |
| Pipeline CDC fim a fim (Oracle → Kafka → ClickHouse) | Frente C. Não faz parte do critério de aceite da Frente A. |

O caminho fim a fim do CDC (Oracle → Kafka → ClickHouse) é escopo da Frente C e
**não** faz parte do critério de aceite desta sessão.

---

## 17. Ambiente em container (`.devcontainer/`)

A máquina de desenvolvimento principal não roda containers (seção 16), e a
alternativa pessoal disponível tem 8 GB de RAM — insuficiente para a pilha
completa. Sem uma saída, a Frente A ficaria escrita mas nunca executada.

**Escolhido:** um `.devcontainer/devcontainer.json` com a feature
`docker-in-docker`, que faz o repositório rodar em **GitHub Codespaces** (pelo
navegador, sem instalar nada) ou em qualquer host Linux via VS Code Dev
Containers. O `docker-compose.yml` não muda em nada: o devcontainer apenas o
embrulha.

Detalhes que não são óbvios:

- **`hostRequirements` pede 4 núcleos / 16 GB / 32 GB de disco.** O tipo padrão
  de 2 núcleos não comporta Oracle + Kafka + Connect + ClickHouse. E o disco
  precisa ser exatamente 32 GB: pedir mais empurra a seleção para o tipo de 8
  núcleos, que consome a cota do Codespaces duas vezes mais rápido sem nenhum
  ganho.
- **Java 8 e Maven vêm instalados**, para que a Frente B comece sem preparo.
- **O download das imagens não é feito no `postCreateCommand`.** Levaria vários
  minutos, e um timeout na criação do Codespace é bem mais difícil de
  diagnosticar do que um `make setup` que falha com mensagem clara.

**Efeito colateral positivo, e o motivo de isso valer para a defesa:** com o
repositório público e o devcontainer versionado, qualquer avaliador reproduz o
ambiente inteiro em um clique, com a própria cota do GitHub. O trabalho deixa de
depender da descrição de um ambiente e passa a ser executável por terceiros.

**Multi-arquitetura:** todas as cinco imagens publicam `linux/amd64` e
`linux/arm64`, verificado nos registries. Windows, Linux, Mac Intel e Mac Apple
Silicon rodam nativamente, sem emulação — o que permite que a dupla trabalhe em
máquinas diferentes.

A restrição que sobra está em `docs/ambientes.md`, seção 5: **os números finais
de benchmark precisam sair de um único ambiente de referência.** Ambientes
diferentes produzem medições que não se combinam.

---

## 18. Datafiles precisam de caminho absoluto dentro do volume

Erro de construção descoberto em 2026-07-21, com o ambiente já validado e em
uso. Custou a perda total de um banco de desenvolvimento.

**O que estava errado.** As três tablespaces criadas pelo init usavam nome de
arquivo relativo:

```sql
CREATE TABLESPACE logminer_tbs DATAFILE 'logminer_tbs_root.dbf' SIZE 25M ...;
```

O Oracle resolve nome relativo a partir de `$ORACLE_HOME/dbs` — que fica na
**camada gravável da imagem**, não no volume nomeado `oracle-data` montado em
`/opt/oracle/oradata`.

**Por que passou despercebido.** Um `docker compose restart` preserva a camada
gravável, e tudo funciona. O `make validate` completo passou com 30 itens. O
problema só aparece quando o container é **recriado** — `docker compose down`
seguido de `up`, troca de versão de imagem, ou reinício do Docker Desktop. Aí a
camada gravável é descartada, os datafiles somem, e o banco entra em loop de
restart:

```
ORA-01157: cannot identify/lock data file 27
ORA-01110: data file 27: '.../dbhomeFree/dbs/logminer_tbs_root.dbf'
```

Sem recuperação prática: a tablespace faz parte do dicionário, o arquivo não
existe mais, e o banco não abre para se consertar.

**Correção.** O diretório é derivado em tempo de execução, do datafile da
SYSTEM — que por construção está dentro do volume:

```sql
COLUMN dbf_dir NEW_VALUE dbf_dir NOPRINT
SELECT SUBSTR(file_name, 1, INSTR(file_name, '/', -1)) AS dbf_dir
FROM   dba_data_files WHERE tablespace_name = 'SYSTEM' AND ROWNUM = 1;

CREATE TABLESPACE logminer_tbs DATAFILE '&dbf_dir.logminer_tbs.dbf' ...;
```

Derivado, e não escrito à mão, porque o layout muda entre versões da imagem — o
caminho já mudou de `dbhomeFree/23ai` para `dbhomeFree/26ai` durante este
próprio trabalho. Antes do `CREATE`, um bloco PL/SQL aborta o init se o
diretório não estiver sob `/opt/oracle/oradata` — melhor falhar na criação do
que entregar um banco que morre no primeiro `down`.

**Detecção.** O `make validate` passa a conferir:

```sql
SELECT count(*) FROM cdb_data_files
 WHERE file_name NOT LIKE '/opt/oracle/oradata/%';   -- tem que ser 0
```

**Lição para o relatório.** Esta é a falha mais instrutiva do trabalho: passou
por revisão estática completa, passou por uma execução bem-sucedida de 30 itens,
e só apareceu num evento de ciclo de vida — recriação de container — que nenhum
teste anterior havia exercitado. Persistência em container não é sobre o que
funciona enquanto o container está de pé; é sobre o que sobrevive quando ele
deixa de existir.

---

# Frente B — log-writer

Sessão de 2026-07-28. As decisões abaixo foram tomadas **depois** da leitura da
`LogPs.inserir(LogDt)` real em `../projudi`, não antes.

---

## 19. Batching e comportamento em falha

A decisão mais pesada da frente, e a única levada explicitamente à decisão
humana em vez de resolvida por conta própria.

**Escolhido:** fila limitada em memória, gravação em lote por thread própria, e
**desvio para um sink de fallback em toda situação anormal**
(`BufferedLogSink`). Com `lote.max = 1`, o mesmo componente degrada para
gravação síncrona — o que dá as duas pontas do benchmark sem código duplicado.

### As alternativas e por que caíram

| Alternativa | Por que não |
|---|---|
| **Síncrono, falha propaga** (semântica de hoje) | Garantia legal máxima e implementação trivial, mas transforma o ClickHouse em dependência de disponibilidade do Projudi inteiro: ClickHouse fora = Projudi fora. Trocar um ponto único de falha conhecido por um novo não é modernizar. |
| **Síncrono com fallback** | Simples e seguro, mas paga 1 round-trip HTTP por log — exatamente o custo que o batching existe para eliminar — e enfraquece o número do benchmark. |
| **Fila + lote, sem fallback** | Melhor desempenho e código mais limpo, mas registro de auditoria que deixa de existir num sistema judicial é dívida indefensável. |

### O que a solução garante — e o que não garante

**Garante:**

- nenhum registro se perde por indisponibilidade do ClickHouse;
- a thread do usuário nunca bloqueia esperando o ClickHouse e nunca recebe
  exceção por causa de log;
- a fila não cresce sem limite.

**Não garante — e este é o ponto que precisa estar escrito com precisão:**

> A durabilidade **não** é absoluta. Contra morte abrupta da JVM (`kill -9`, OOM
> killer, queda de energia), o que estiver em memória e ainda não gravado se
> perde.

**Os dois números que delimitam a janela:**

| Número | Padrão | O que delimita |
|---|---|---|
| `fila.capacidade` | 10.000 | teto de registros aguardando flush |
| `lote.max` | 500 | registros já retirados da fila, em voo no lote corrente |

**Pior caso: `fila.capacidade + lote.max` = 10.500 registros.** Em regime
normal, com tráfego constante, a perda real é o que entrou nos últimos
`lote.intervaloMs` (padrão: 1.000 ms), porque a fila não acumula.

O encerramento ordenado (`LogWriter.encerrar()`, chamado de
`contextDestroyed`) drena a fila e fecha a janela. Não há shutdown hook
automático: um hook segurando referência ao sink impede a coleta do classloader
da aplicação no undeploy do Tomcat.

### Política de saturação: explícita, nunca silenciosa

Fila cheia significa que o ClickHouse não está dando conta do ritmo de entrada.
O registro vai **direto ao fallback, na thread chamadora**. Três coisas que
isso deliberadamente **não** faz:

- não deixa a fila crescer (ela é uma `ArrayBlockingQueue` limitada);
- não bloqueia a thread chamadora à espera de vaga (`offer`, não `put`);
- não descarta em silêncio.

Escrever no Oracle na thread do usuário é o mesmo custo que o Projudi já paga
hoje em 100% das gravações — no caminho de saturação isso é o comportamento
atual, não uma regressão.

### Fallback observável

Todo registro que cai no Oracle é contável, por duas vias:

- `Metricas.getGravadosFallback()`, separado em `desviosPorSaturacao` e
  `desviosPorFalha`;
- um logger dedicado, `projudi.logwriter.FALLBACK`, para roteamento sem filtro
  de texto.

Não é adorno. Durante a transição, **"quantos logs foram pelo caminho velho" é
métrica operacional e material do relatório**: sem ela, um ClickHouse
intermitente vira um desvio silencioso para o Oracle, e o ganho medido não
corresponde ao que aconteceu de fato.

`Metricas.getPerdidos()` é o único caminho de perda com o sink ativo — falha no
destino **e** no fallback — e por isso é o número que precisa ser zero.

### Limitação conhecida: a semântica de transação muda

Hoje o log grava na **mesma conexão e na mesma transação** da operação de
negócio (`LogNe.salvar` usa `FabricaConexao.PERSISTENCIA`, e a `LogPs` recebe
essa conexão no construtor). Consequência: rollback do negócio desfaz o log
junto.

Com o writer, a gravação acontece fora daquela transação. Logo:

> **Um log pode chegar ao ClickHouse mesmo que a operação de negócio sofra
> rollback depois.**

Não é resolvido no MVP, e fica registrado como limitação conhecida em vez de
ser omitido. A mitigação futura é óbvia e barata: chamar o writer apenas após o
commit da transação de negócio, em vez de durante. Isso exige um ponto de
gancho pós-commit que o Projudi hoje não tem, o que é trabalho de escopo
próprio.

Vale notar a assimetria: hoje o rollback também apaga o registro de uma
tentativa que *aconteceu*. Qual dos dois comportamentos é o correto para
auditoria é uma discussão de requisito, não de implementação — mas a mudança
precisa ser conhecida antes de ser aceita.

---

## 20. ID_LOG gerado no cliente

O ClickHouse não tem sequence, e hoje o ID nasce no Oracle:
`executarInsert(sql, "ID_LOG", ps)` envolve o INSERT em
`BEGIN … RETURNING ID_LOG INTO ?; END;` (`Persistencia.java:581-587`), e o
valor volta para `dados.setId(...)`, que a `LogNe` consome.

**Escolhido:** identificador de 64 bits montado no processo, estilo Snowflake:

```
(millisDesde2020-01-01Z << 22) | (workerId << 12) | sequencia
        41 bits                    10 bits          12 bits
```

Justificativas:

- **Zero round-trip**, coerente com a decisão de não depender do Oracle na
  escrita — e é o **único esquema compatível com gravação assíncrona**, porque
  o ID precisa existir no instante em que a `LogPs` retorna, não quando o lote
  é gravado.
- **Monotônico crescente**: preserva o desempate do
  `ORDER BY (HORA, ID_USU, ID_LOG)` da `log_raw` e a correlação temporal.
- **Cabe em `UInt64` e em `NUMBER(24)`** nos dois lados.
- **Faixa disjunta do histórico**, o que elimina colisão na migração futura.
- **Mantém o contrato `dados.setId(...)`**.
- **Habilita idempotência** do reenvio de lote e a verificação de completude do
  benchmark por `count(DISTINCT ID_LOG)`.

Rejeitadas: sequence do Oracle (reintroduz o acoplamento que a solução quer
remover); sem ID (quebra `setId()`, perde o desempate e inviabiliza a
verificação de completude); UUID (128 bits não cabem, e a aleatoriedade destrói
a localidade da chave de ordenação).

`workerId` é configurável por instância; ausente, é derivado de hostname+PID
**com aviso no log** — com 1024 slots e várias JVMs contra o mesmo destino, a
colisão não é impossível. Testes cobrem unicidade entre dois workers no mesmo
milissegundo e o comportamento definido no estouro dos 4096 slots (espera o
milissegundo seguinte; nunca reutiliza).

### Verificação da numeração atual — correção em dois sentidos

Esta subseção registra um erro corrigido durante a decisão. **Fica aqui de
propósito**, como as decisões 5 e 18: o histórico da correção vale mais que uma
decisão que finge ter nascido certa, e o caso é material de metodologia — mostra
que "ler o código" e "ler a condição que protege o código" são coisas
diferentes.

A pergunta feita antes de implementar foi: gravar um ID explícito na
`PROJUDI.LOG` pelo fallback quebra a numeração legada?

**Resposta: não.** O trigger real, em `../projudi/BancoDeDados/07_CreateTrigger.sql`:

```sql
CREATE OR REPLACE TRIGGER "PROJUDI"."LOG_ID_LOG_TRG" BEFORE INSERT OR UPDATE ON LOG
FOR EACH ROW
...
  IF INSERTING AND :new.Id_Log IS NULL THEN
    SELECT Log_Id_Log_SEQ.NEXTVAL INTO v_newVal FROM DUAL;
    ...
    :new.Id_Log := v_newVal;
  END IF;
```

Ele **só atribui quando o ID vem `NULL`**. Um ID preenchido não é sobrescrito e
**não consome `LOG_ID_LOG_SEQ.NEXTVAL`** — a sequence é independente do
`MAX(ID_LOG)` da tabela, então gravar um valor na casa de 10¹⁷ não empurra a
numeração dos inserts legados. O fallback pode e deve carregar o ID gerado pelo
writer, o que preserva rastreabilidade e permite deduplicação.

Três afirmações caíram nessa verificação, em dois sentidos. Ficam registradas em
vez de apagadas:

1. **Erro de origem: "usar a `PROJUDI.SEQ_LOG` do Projudi" foi oferecido como
   alternativa.** Essa sequence não é de produção. A `PROJUDI.SEQ_LOG` é do
   **laboratório** — criada por `infra/oracle/init/sql/40_pdb_tables.sql` e
   documentada na decisão 13 como inexistente no schema real. Apontar um objeto
   de banco de produção que não existe, num trabalho que vai a banca, é o tipo
   de afirmação que precisa ser verificada antes, não corrigida depois.

2. **Correção da correção: existe, sim, uma sequence — só não com aquele nome.**
   A premissa que substituiu o erro acima foi "não existe sequence; a geração é
   trigger com `MAX(ID_LOG)+1`". A primeira metade é verdadeira apenas quanto ao
   **nome** `SEQ_LOG`. O objeto real é
   **`PROJUDI.LOG_ID_LOG_SEQ`** — `BancoDeDados/01_CreateSequence.sql:473`,
   `START WITH 104620234 NOCACHE NOORDER NOCYCLE`.

3. **O `MAX+1` é um ramo de *bootstrap*, inalcançável.** O caminho ordinário é
   `LOG_ID_LOG_SEQ.NEXTVAL`. O `MAX(ID_LOG)+1` está mesmo escrito no trigger,
   mas dentro de `IF v_newVal = 1` — previsto para o caso de a sequence ter
   acabado de ser criada e ainda estar em 1. Com `START WITH 104620234`, esse
   ramo nunca executa.

O item 3 é o mais instrutivo, e o motivo de esta subseção existir: `MAX+1` está
literalmente no código, e uma leitura rápida do trigger *confirmaria* a
premissa. É preciso ler a **condição que protege o bloco** para ver que ele não
roda. Ler o código e ler a guarda do código não são a mesma coisa.

### O que isso muda na decisão

**Nada.** A escolha do Snowflake não se apoiava na ausência de uma sequence; ela
se apoia nas razões listadas acima — zero round-trip, monotonicidade, faixa
disjunta, contrato do `setId(...)` e idempotência do reenvio de lote — e todas
seguem válidas com a `LOG_ID_LOG_SEQ` existindo. A rejeição de "usar a sequence
do Oracle" também não muda de motivo: ela cai por **reintroduzir a ida ao banco
transacional na escrita**, não por a sequence não existir.

O que muda é a **razão pela qual gravar o ID explícito é seguro**. A preocupação
levantada era que um ID Snowflake gravado na `LOG` empurraria o `MAX` e
quebraria a numeração dos inserts legados. Isso valeria se a geração fosse
`MAX+1`. Como é `NEXTVAL`, **a sequence é independente do `MAX(ID_LOG)` da
tabela**: gravar um valor na casa de 10¹⁷ não a afeta, e um ID preenchido nem
chega a consumir `NEXTVAL`, porque a guarda `IS NULL` impede a entrada no
bloco. Seguro nos dois cenários — por motivos diferentes, e agora pelo motivo
certo.

---

## 21. Feature flag de três estados

**Escolhido:** `ORACLE | CLICKHOUSE | AMBOS`, com **`ORACLE` como padrão**.
Subir o jar no classpath sem configurar nada não muda comportamento nenhum;
ligar a Solução 1 é um ato explícito.

O modo `AMBOS` existe para o período de sombra em homologação: os dois destinos
recebem o mesmo registro, com o **mesmo `ID_LOG`**, o que permite comparar
Oracle e ClickHouse linha a linha por chave, e não por amostragem. Numa
migração de log de auditoria, essa comparação é a evidência que justifica
desligar o destino antigo.

**Divisão de responsabilidade no modo `AMBOS`:** a biblioteca grava apenas no
ClickHouse; a cópia no Oracle é feita pela própria `LogPs`, executando o mesmo
código que executa hoje. Não é preguiça de composição — é o que mantém a cópia
Oracle dentro da transação de negócio, como sempre foi, e garante que o modo
sombra compare o ClickHouse contra o comportamento **real** de produção, e não
contra uma reimplementação dele. Daí o método `LogDestino.gravaNoOracle()`, que
a `LogPs` consulta para decidir se cai no caminho legado depois de chamar o
writer.

Para a comparação fechar por chave, o INSERT legado precisa passar a incluir
`ID_LOG` com o valor devolvido pelo writer — seguro pelo que ficou estabelecido
na decisão 20.

O `CompositeLogSink` existe para o laboratório e o benchmark, onde não há
`LogPs` e o `OracleLogSink` é autossuficiente.

---

## 22. `ojdbc8` no benchmark, não `ojdbc11`

A decisão 1 fixou `ojdbc11:23.8.0.25.04` para a imagem do Kafka Connect. No
`log-writer` isso **não serve**: o `ojdbc11` exige JDK 11, e este módulo compila
e roda em Java 8 (decisão 14).

**Escolhido:** `com.oracle.database.jdbc:ojdbc8:23.5.0.24.07`, a última linha
publicada para JDK 8, em **escopo `test`**. O Connect continua com o `ojdbc11`;
são classpaths independentes.

O escopo `test` é possível porque **nada em `src/main` importa classes da
Oracle** — o `OracleLogSink` usa só `java.sql`. O driver é necessário apenas
para o grupo de controle do benchmark e para o teste de integração, e não entra
no jar que vai para o Projudi.

---

## 23. Testabilidade sem banco: `ConexaoSupplier` e proxy dinâmico

Requisito da frente: `mvn test` verde sem ClickHouse nem Oracle de pé.

A costura é a interface `ConexaoSupplier`, que os sinks recebem no construtor em
vez de chamarem `DriverManager` diretamente. O teste injeta um `JdbcFalso` que
monta `Connection` e `PreparedStatement` com `java.lang.reflect.Proxy` e
registra cada `setXxx(indice, valor)`.

Por que proxy dinâmico e não uma classe de mentira escrita à mão: implementar
`PreparedStatement` exige mais de 50 métodos vazios, e cada novo método na
interface entre versões do JDBC quebraria a compilação. O proxy registra tudo em
poucas linhas e é indiferente a isso.

O ganho não é só de conveniência — permite verificar o que realmente importa
sem banco: o SQL exato, a **ordem** das 13 colunas, o **tipo** de cada ligação
(`setTimestamp` na `DATA` do ClickHouse contra `setDate` na `DATA` do Oracle,
que é a diferença real entre os dois destinos), e que os CLOBs saem idênticos ao
que entrou.

Como efeito colateral, o `ConexaoSupplier` é também o ponto de extensão para o
Projudi usar o pool que já existe lá (`FabricaConexao`) em vez do
`DriverManager`, se um dia a gravação de log passar a compartilhar pool com o
resto da aplicação.

---

## 24. Resolução do `ID_LOG_TIPO` no ClickHouse, não no Oracle

A `LogPs.inserir` tem dois caminhos para essa coluna: ou a `LogDt` traz o
`ID_LOG_TIPO`, ou o INSERT resolve na hora com um subselect —
`(SELECT MAX(ID_LOG_TIPO) FROM PROJUDI.LOG_TIPO WHERE LOG_TIPO_CODIGO = ?)`
(`LogPs.java:71-75`). O segundo é o caminho comum: a maioria dos chamadores
constrói a `LogDt` com `logTipoCodigo`, não com o id.

**Escolhido:** resolver contra a dimensão `projudi_logs.log_tipo` **do
ClickHouse**, com cache em memória. Manter o subselect no Oracle significaria
uma ida ao banco transacional a cada log — exatamente o que a Solução 1 existe
para eliminar. A dimensão já é espelhada no ClickHouse (decisão 13) e muda
raramente.

Cache sem expiração e sem limite de tamanho, de propósito: são algumas dezenas
de linhas (a sequence de produção está em 44) e a tabela é recarregada por
inteiro quando muda. Um *miss* **não** é cacheado — a dimensão pode ser
recarregada sem restart, e insistir num zero cacheado transformaria um atraso de
carga em dano permanente aos registros daquele tipo.

Código desconhecido grava `ID_LOG_TIPO = 0` e incrementa um contador, em vez de
falhar: um tipo de log novo não é motivo para perder o registro de auditoria
inteiro, e o zero fica detectável por consulta.

---

## 25. `slf4j-api` é obrigatório, e o driver do ClickHouse falha em silêncio sem ele

Erro de construção descoberto em 2026-07-28, na **primeira execução do teste de
integração num computador com Docker**. A verificação estática e os 50 testes
unitários não o pegaram, pela mesma razão de sempre: só se manifesta quando
alguém tenta abrir uma conexão de verdade.

**O sintoma:**

```
java.sql.SQLException: No suitable driver found for jdbc:ch://localhost:8123/projudi_logs
```

com o `clickhouse-jdbc` presente e visível no `mvn dependency:tree`.

### Três hipóteses, duas erradas

| Hipótese | Verificação | Veredito |
|---|---|---|
| Dependência ausente ou escopo errado | `dependency:build-classpath` mostra o `clickhouse-jdbc-0.7.2-all.jar` no classpath de teste | ❌ |
| O `META-INF/services/java.sql.Driver` não sobrevive ao shade | `unzip -p …-all.jar META-INF/services/java.sql.Driver` imprime `com.clickhouse.jdbc.ClickHouseDriver` e `com.clickhouse.jdbc.Driver`; as classes estão nos pacotes originais | ❌ |
| O `<clinit>` do driver falha | `Class.forName("com.clickhouse.jdbc.ClickHouseDriver")` num `main` isolado, com o mesmo classpath | ✅ |

A terceira devolveu a causa:

```
java.lang.NoClassDefFoundError: org/slf4j/LoggerFactory
  causa: java.lang.ClassNotFoundException: org.slf4j.LoggerFactory
  at com.clickhouse.jdbc.ClickHouseDriver.<clinit>(ClickHouseDriver.java:13)
```

### A causa

O uber jar `all` **não empacota o `org.slf4j`**. Ele traz
`com/clickhouse/logging/Slf4jLogger.class`, que *referencia* a API, e o próprio
`clickhouse-jdbc-0.7.2.pom` declara `slf4j-api` como exclusão em cada uma de
suas dependências — ou seja, o driver espera que o **ambiente** forneça a API de
log. É uma escolha razoável para uma biblioteca: a fachada de log é do
integrador, não dela.

A decisão 14 excluiu **todas** as transitivas do artefato `all`
(`<exclusion><groupId>*</groupId><artifactId>*</artifactId></exclusion>`) para
garantir que nenhuma versão não sombreada voltasse pelo Maven. A exclusão está
certa no motivo e larga demais no alcance: levou junto a única coisa que o uber
jar realmente precisava receber de fora.

### Por que o sintoma apontava para o lugar errado

Esta é a parte que vale para o relatório.

O `DriverManager` carrega os drivers por SPI dentro de um laço que **captura e
descarta `Throwable`**. Um provedor cujo inicializador estático explode é
simplesmente pulado, sem log e sem rastro. O resultado é que um
`NoClassDefFoundError` de uma classe de *logging* se apresenta como
`No suitable driver found for jdbc:ch://…` — uma mensagem que aponta para a URL,
depois para a dependência, e nunca para a classe que faltou.

Some-se a isso que o `DriverManager` faz essa varredura **uma única vez**, no
próprio inicializador estático, com o *context classloader* daquele instante:
sob Surefire e sob `exec:java`, que montam classloaders próprios, um driver
carregado depois nunca entra por SPI.

### Correção, em duas partes

**1. A dependência que faltava** (`log-writer/pom.xml`):

```xml
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-api</artifactId>
    <version>1.7.25</version>
    <scope>provided</scope>
</dependency>
```

Escopo `provided`, não `compile`, por dois motivos:

- o Projudi **já** tem `org.slf4j:slf4j-api:1.7.25` em `WEB-INF/lib` (verificado
  no `pom.xml` daquele repositório e no artefato construído). Exportar a
  dependência arriscaria duas versões no mesmo classpath — exatamente o que a
  decisão 14 quer evitar;
- `provided` está presente na compilação e nos testes deste módulo, que é onde o
  driver precisa carregar por conta própria.

Consequência a registrar: **dentro do Projudi o driver carregaria normalmente**,
porque o slf4j já está lá. A falha era específica do classpath isolado do
`log-writer` — o que explica por que ela só apareceu ao rodar o teste de
integração, e não ao pensar na integração com a aplicação.

Versão fixada em 1.7.25, a mesma do Projudi, para que o teste exercite o que a
produção vai fornecer.

**2. Registro explícito do driver** (`ConexaoSupplier.DoDriverManager`):

Um bloco estático carrega `com.clickhouse.jdbc.ClickHouseDriver` e
`oracle.jdbc.OracleDriver` por `Class.forName`, em melhor esforço, guardando o
motivo de cada falha. Quando alguém pede uma conexão para uma URL cujo driver
não carregou, sai uma `LogWriterException` que nomeia a classe, a URL, o
artefato Maven **e a causa raiz** — em vez do `No suitable driver found`.

Isso não é redundância em relação à parte 1. São coisas diferentes:

- a parte 1 conserta **este** problema;
- a parte 2 garante que o **próximo** problema desse tipo se apresente com o
  nome certo. Qualquer falha de inicialização de driver — conflito de versão,
  outra classe ausente, jar corrompido — deixa de ser engolida pelo laço do SPI.

A carga é por tentativa, nunca exigência: este supplier atende ClickHouse e
Oracle, e o `ojdbc8` está em escopo `test`. Exigir os dois na inicialização
quebraria o uso normal da biblioteca dentro do Projudi, onde só o ClickHouse
importa.

### Cobertura de teste

`ConexaoSupplierTest` fixa a regressão **sem ClickHouse no ar**: verifica que,
depois de tocar o `DoDriverManager`, existe no `DriverManager` um driver que
aceita `jdbc:ch://…` e `jdbc:clickhouse://…`, e que uma tentativa de conexão
contra uma porta fechada falha por *conexão*, não por driver ausente. Os sete
testes falhavam antes da correção e passam depois — a falha foi reproduzida na
máquina sem Docker, o que era a única forma de tratá-la com o ciclo curto.

### O padrão, pela terceira vez

É a terceira suposição deste trabalho que sobrevive à revisão estática e cai na
primeira execução real, junto com as variáveis `ENABLE_ARCHIVELOG` que a imagem
nunca lê (decisão 5) e os datafiles com caminho relativo (decisão 18). As três
têm a mesma forma: **o artefato estava correto pelo que dava para inspecionar, e
errado pelo que só o ambiente sabe**. A diferença aqui é o agravante de a
plataforma ter engolido o erro — não bastava executar, era preciso executar e
não acreditar na mensagem.

---

## 26. Bind mount sobre `config.d/` escondia o `docker_related_config.xml`

Erro de construção da Frente A, descoberto em 2026-07-28 pela Frente B, na
**primeira vez que um cliente tentou falar com o ClickHouse a partir do host**.
Sobreviveu ao `make validate` com 32 itens verdes, ao healthcheck do compose e a
seis dias de ambiente no ar.

### O sintoma

```
$ curl -v http://localhost:8123/ping
* Connected to localhost (::1) port 8123
* Recv failure: Connection reset by peer
```

E, pelo JDBC, `SQLException: Connection reset` com 40 quadros de pilha. Ao mesmo
tempo:

```
$ docker compose ps
projudi-clickhouse  ... Up 18 minutes (healthy)   0.0.0.0:8123->8123/tcp
```

Container saudável, porta publicada, e nenhum cliente do host conseguindo falar
com ele.

### A causa

O compose montava os **diretórios** de configuração:

```yaml
- ./clickhouse/config/config.d:/etc/clickhouse-server/config.d:ro
- ./clickhouse/config/users.d:/etc/clickhouse-server/users.d:ro
```

Um bind mount de diretório **substitui** o conteúdo do diretório no container,
não se funde com ele. A imagem oficial instala em `config.d/` o arquivo
`docker_related_config.xml`, que contém:

```xml
<listen_host>::</listen_host>
<listen_host>0.0.0.0</listen_host>
<listen_try>1</listen_try>
```

Sem ele, vale o `listen_host` padrão do `config.xml`: apenas `127.0.0.1` e
`::1` — localhost **de dentro do container**. O `docker-proxy` aceita a conexão
no host, tenta repassá-la para o IP do container na rede bridge, é recusado, e
derruba o cliente com RST.

**A prova estava no próprio log do container**, e passou despercebida por seis
dias:

```
Processing configuration file '/etc/clickhouse-server/config.xml'.
Merging configuration file '/etc/clickhouse-server/config.d/10-projudi.xml'.
```

Num container saudável haveria uma segunda linha `Merging …
docker_related_config.xml`. A ausência de uma linha é o tipo de evidência que
ninguém procura.

O mesmo mount tinha um problema irmão, esse com mensagem explícita e igualmente
ignorada:

```
/entrypoint.sh: line 147: /etc/clickhouse-server/users.d/default-user.xml: Read-only file system
```

O entrypoint precisa **escrever** em `users.d/`, e o `:ro` no diretório impede.

### Por que o `make validate` não pegou

Porque **todas** as suas checagens de ClickHouse falam com o servidor por dentro:

```bash
ch() { docker compose exec -T clickhouse clickhouse-client --query "$1"; }
```

O healthcheck do compose tem o mesmo vício:

```yaml
test: ["CMD", "clickhouse-client", "--query", "SELECT 1"]
```

Ambos conectam por localhost dentro do container, onde o servidor **estava**
escutando. Os 32 itens verdes eram verdadeiros e irrelevantes para a única
pergunta que importava à Solução 1: *um cliente JDBC no host consegue gravar?*

A Frente A validou o ambiente pelo lado de dentro. O primeiro consumidor real
chegou pelo lado de fora.

### Correção

**1. Montar arquivo a arquivo, nunca o diretório:**

```yaml
- ./clickhouse/config/config.d/10-projudi.xml:/etc/clickhouse-server/config.d/10-projudi.xml:ro
- ./clickhouse/config/users.d/10-access-management.xml:/etc/clickhouse-server/users.d/10-access-management.xml:ro
```

Preserva o que a imagem instala e devolve a `users.d/` a permissão de escrita que
o entrypoint precisa. Ressalva já registrada na decisão 4: bind mount de arquivo
cujo caminho no host não existe faz o Docker criar um **diretório** com aquele
nome. Os dois arquivos são versionados, então isso não acontece aqui — mas quem
renomear um deles sem ajustar o compose vai encontrar esse comportamento.

**2. Checagem de acesso pelo host no `make validate`:**

```bash
curl -sS --max-time 10 "http://localhost:${CH_HTTP_PORT}/ping"   # tem que responder "Ok."
```

É a correção mais importante das duas. Sem ela, o mesmo erro — ou qualquer outro
que afete só o caminho externo — volta a passar despercebido. O item falha com
o comando de diagnóstico junto, e degrada para aviso quando não há `curl`.

### Aplicação

A correção exige **recriar** o container, não reiniciar: bind mounts são
resolvidos na criação.

```bash
docker compose --env-file .env -f infra/docker-compose.yml up -d --force-recreate clickhouse
```

Os volumes nomeados são preservados, então não há perda de dados nem
reexecução dos DDLs.

### Lição

É a quarta suposição do trabalho que só cai em execução real (decisões 5, 18, 25
e esta), e a segunda em que a **verificação existia e olhava para o lado
errado**. A decisão 18 dizia: persistência em container não é sobre o que
funciona enquanto o container está de pé, é sobre o que sobrevive quando ele
deixa de existir. Esta acrescenta o par: **acessibilidade em container não é
sobre o que responde de dentro, é sobre o que responde de fora** — e um
healthcheck que só olha para dentro atesta exatamente aquilo que nunca esteve em
dúvida.

### Pendência relacionada, não resolvida

O log do init traz também:

```
/docker-entrypoint-initdb.d/90_app_user.sh: line 27: default: command not found
```

A linha 27 é a invocação do `clickhouse-client` com heredoc, e o usuário
`projudi_app` **é criado com sucesso** (a linha seguinte confirma, e o
`make validate` verifica). Não é a causa de nada observado até aqui e não foi
diagnosticado — o script é executado via `source` pelo entrypoint, e a mensagem
sugere alguma interação entre esse contexto e a expansão da linha. Fica
registrado como ruído conhecido, a investigar com:

```bash
docker compose exec clickhouse sh -c 'sed -n "140,160p" /entrypoint.sh'
```
