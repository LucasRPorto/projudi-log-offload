# Onde rodar o ambiente e como testar

Este documento existe porque a máquina de desenvolvimento principal não pode
rodar containers, e porque a forma de testar cada frente depende de **onde** o
ambiente está — em especial a Frente B, cujo entregável é uma comparação de
desempenho.

---

## 1. A restrição

O notebook do TJ-GO (`TJGO\lrporto`) é um domínio corporativo com usuário sem
privilégio administrativo. Verificado em 2026-07-21:

| Item | Estado |
|---|---|
| Grupo Administradores local | não inclui o usuário de domínio |
| `Microsoft-Windows-Subsystem-Linux` | **desabilitado** (`InstallState = 2`) |
| `VirtualMachinePlatform` | **desabilitado** |
| `HypervisorPlatform` | **desabilitado** |
| `Microsoft-Hyper-V-All` | **desabilitado** |

Habilitar qualquer um desses recursos exige `DISM` elevado e reinício. Isso
elimina **todos** os runtimes de container no Windows — Docker Desktop, Podman
Desktop e Rancher Desktop dependem de WSL2 ou Hyper-V.

Não há workaround sem o TI. O ClickHouse também não ajuda aqui: não existe build
nativo para Windows, ele só roda em Linux, macOS ou FreeBSD.

---

## 2. Os três ambientes

| | Máquina pessoal | GitHub Codespaces | Máquina do TJ-GO |
|---|---|---|---|
| Roda o compose | ✅ | ✅ | ❌ |
| Custo | zero | cota mensal | — |
| Limite de tempo | nenhum | 30 h/mês (Free) · 45 h/mês (Pro) | — |
| Benchmark confiável | ✅ | ✅ | ❌ |
| Outras pessoas testam | não | ✅ cada um com a própria cota | — |
| Precisa de internet | não | sim | — |
| Roda o Projudi no Eclipse | se instalado | inviável na prática | ✅ |
| Acessa a base dev/homolog do TJ-GO | só via VPN | ❌ rede corporativa | ✅ |

### Cotas do Codespaces (verificadas na documentação do GitHub)

- **GitHub Free:** 120 core-hours/mês + 15 GB-month de armazenamento
- **GitHub Pro:** 180 core-hours/mês + 20 GB-month

O consumo é multiplicado pelo número de núcleos. Este ambiente exige o tipo de
**4 núcleos / 16 GB** (o de 2 núcleos não comporta Oracle + Kafka + Connect +
ClickHouse — o Oracle é morto pelo OOM killer). Logo:

> 120 ÷ 4 = **30 horas por mês** no plano Free
> 180 ÷ 4 = **45 horas por mês** no plano Pro

Dois pontos práticos:

- **Pro sai de graça** pelo GitHub Student Developer Pack, para quem tem
  vínculo acadêmico comprovável. Vale solicitar: são 50% a mais de cota.
- **Armazenamento é cobrado enquanto o codespace existir**, mesmo parado. Um
  codespace de 32 GB mantido o mês inteiro estoura os 15 GB-month do Free.
  Apague o codespace ao terminar cada sessão — os volumes se perdem, mas
  `make up` recria tudo a partir dos DDLs.
- O timeout de ociosidade padrão é **30 minutos**. Para não perder o ambiente no
  meio de um experimento, suba para 90–240 min em
  *Settings → Codespaces → Default idle timeout*.

### Quem paga quando outra pessoa testa

Quem cria o codespace. Cada pessoa que abrir o repositório no Codespaces
consome a **própria** cota — ninguém consome a sua. Para um TCC isso é ideal:
com o repositório público e o `.devcontainer/` versionado, a banca ou qualquer
avaliador reproduz o ambiente inteiro em um clique, sem instalar nada.

---

## 3. O ponto metodológico do benchmark

Este é o motivo pelo qual a escolha do ambiente não é só conveniência.

O Projudi, quando roda localmente no Eclipse, **aponta para a base Oracle de
desenvolvimento ou homologação do TJ-GO** — nunca para uma base local. Isso cria
uma armadilha na hora de medir:

```
   ❌ COMPARAÇÃO INVÁLIDA

   Projudi (notebook TJ) ──── LAN corporativa ────► Oracle dev (TJ-GO)
                         └─── internet + túnel ───► ClickHouse (Codespace)

   O ClickHouse perde por causa do RTT da internet, não por mérito técnico.
   E se as posições se invertessem, ele ganharia pelo mesmo motivo errado.
```

Comparar um destino na LAN corporativa com outro do outro lado da internet não
mede nada sobre Oracle × ClickHouse: mede latência de rede. O resultado seria
descartado em qualquer arguição.

```
   ✅ COMPARAÇÃO VÁLIDA

   harness de benchmark ──► Oracle      (container, mesmo host)
                        └─► ClickHouse  (container, mesmo host)

   Mesma máquina, mesma pilha de rede, mesma janela de tempo.
```

**Consequência:** o container Oracle deste repositório não é um brinquedo nem
redundância em relação à base de desenvolvimento do TJ-GO. Ele é o **grupo de
controle** do experimento — a única forma de isolar a variável que interessa.

**Consequência 2:** o benchmark da Frente B não deve depender de rodar o Projudi
inteiro. O `log-writer` é uma biblioteca; a medição é um harness próprio dentro
de `log-writer/src/test/`, que escreve N registros nos dois destinos e cronometra
os dois. Isso roda igual na máquina pessoal e no Codespace, e não exige Eclipse,
Tomcat nem a base do TJ-GO.

A integração com o Projudi de verdade é uma validação **funcional**, separada da
medição — ver a seção seguinte.

---

## 4. Como testar cada frente

### Frente B — `log-writer`

Divide-se em dois testes com propósitos diferentes:

**B.1 — Medição de desempenho** (o número que vai para o relatório)

- Onde: máquina pessoal ou Codespace, indiferente
- Contra: o Oracle e o ClickHouse **do compose**, no mesmo host
- Como: harness em `log-writer/src/test/`, executado com `mvn test`
- Não envolve: Eclipse, Projudi, rede do TJ-GO

**B.2 — Validação funcional com o Projudi real** (a prova de que integra)

- Onde: notebook do TJ-GO, Projudi no Eclipse como você já faz hoje
- A base de dados do Projudi continua sendo a de desenvolvimento/homologação —
  nada muda aí
- O que muda: a `LogPs` grava o log num ClickHouse, que precisa estar acessível
  a partir do notebook. Duas formas:

  | Forma | Como | Serve para |
  |---|---|---|
  | ClickHouse no Codespace | porta 8123 encaminhada como pública; a URL HTTPS `https://<codespace>-8123.app.github.dev` funciona como endpoint JDBC | ✅ validação funcional · ❌ medição |
  | ClickHouse na máquina pessoal, na mesma rede | apontar o JDBC para o IP local | ✅ funcional · ⚠️ medição só se o Oracle também for local |

  A validação funcional responde "o log chega íntegro, no formato certo, sem
  quebrar o fluxo do Projudi?". Latência aqui é irrelevante.

### Frente C — Pipeline CDC

Totalmente contida no compose: Oracle → Debezium → Kafka → ClickHouse. Nenhum
componente precisa falar com a máquina do usuário ou com a rede do TJ-GO.

- Onde: máquina pessoal ou Codespace, indiferente
- Como: `./scripts/register-connector.sh`, depois os blocos da seção 5 de
  `validacao/02_oracle_origem.sql` para gerar movimento, e
  `validacao/03_consultas_cdc.sql` para conferir a chegada

**Sobre apontar o Debezium para a base dev/homolog do TJ-GO:** é tecnicamente
possível e daria dados realistas, mas exige de um DBA, na base compartilhada:
`ALTER DATABASE ARCHIVELOG`, supplemental logging `(ALL) COLUMNS` na `PROC`, e
um usuário LogMiner com os grants de `infra/oracle/init/sql/20_cdb_debezium_user.sql`.
São mudanças em configuração de redo log de um ambiente compartilhado, com
custo de armazenamento adicional. Trate como uma etapa **posterior** à validação
no ambiente controlado, com chamado formal — não como caminho do MVP.

---

## 5. Recomendação

**Ambiente principal: máquina pessoal com Docker Desktop.**

Requisito real: **16 GB de RAM** (12 GB dedicados ao Docker) e ~15 GB de disco.
Com 8 GB não roda a pilha completa — no máximo ClickHouse isolado.

Por quê:

- sem cota, sem timeout, sem depender de internet;
- benchmark metodologicamente defensável, com Oracle e ClickHouse no mesmo host;
- ciclo de desenvolvimento rápido (`make reset && make up` sem pensar em custo);
- é onde a Frente C, que é a mais demorada de depurar, deve viver.

**Ambiente secundário: GitHub Codespaces.**

Não como plano B, mas para três coisas que a máquina pessoal não faz:

1. trabalhar a partir do notebook do TJ-GO, sem depender do TI;
2. **reprodutibilidade** — repositório público + `.devcontainer/` significa que
   qualquer pessoa reproduz o ambiente em um clique, com a própria cota. Para
   um TCC isso vale como artefato de defesa;
3. validação funcional B.2, expondo o ClickHouse por HTTPS para o Projudi que
   roda no notebook.

**Máquina do TJ-GO: apenas Eclipse + Projudi**, como já é hoje, apontando para a
base de desenvolvimento/homologação. Nada de infraestrutura roda ali.

O mesmo repositório atende os três: o `docker-compose.yml` é idêntico, o
`.devcontainer/` só embrulha ele para o Codespaces.
