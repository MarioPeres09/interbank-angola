# InterBank Angola

Estou a desenvolver o InterBank Angola para a disciplina de Sistemas Distribuídos II. Quero simular uma transferência entre dois bancos fictícios. No fluxo que defini, o Banco Kwanza inicia a transferência e o Banco Horizonte valida a conta de destino e regista o crédito. Vou usar uma base de dados PostgreSQL para cada banco e uma API REST para a comunicação entre eles.

## Arranque local com um comando

Com o PostgreSQL a correr, executo `arrancar.cmd` no CMD do VS Code, a partir da raiz do projecto. Na primeira execução é criada uma cópia do modelo [`.env.local.example`](.env.local.example). Preencho o ficheiro `.env.local` com os seis valores que já usava nos terminais, no formato `NOME=valor`, sem escrever `set`. Se uma palavra-passe contiver `#`, escrevo o valor entre aspas. O Git ignora este ficheiro.

```bat
arrancar.cmd --check
arrancar.cmd
```

O comando abre quatro janelas CMD para a rede interbancária, o Kwanza, o Horizonte e o Angular. O Angular instala as suas dependências no primeiro arranque, se necessário. Quando os serviços estiverem prontos, abro [InterBank Angola](http://127.0.0.1:4200/).

Se os serviços já estiverem a correr, fecho os terminais anteriores antes de usar o comando. Para parar um serviço, uso `Ctrl+C` na janela correspondente. Quando voltar a trabalhar no projecto, basta correr `arrancar.cmd` novamente.

## Painel gráfico de serviços

Com o PostgreSQL a correr e o `.env.local` preenchido, faço duplo clique em **Abrir-Controlo.vbs** no Explorador do Windows. O atalho compila e abre o painel Angular em [127.0.0.1:4199/controlo](http://127.0.0.1:4199/controlo).

No painel, uso **Arrancar** e **Parar** em cada serviço. Posso parar a rede para testar a recuperação enquanto os bancos e o painel continuam disponíveis. Também posso parar o Angular dos clientes na porta `4200` sem perder o painel. Fechar o separador do navegador mantém os serviços a correr.

Se arranquei um serviço num CMD, o painel mostra **Processo externo**. Para o gerir pelo painel, primeiro paro-o nesse CMD e depois carrego em **Arrancar**. Se o atalho não abrir o painel, posso executar `controlo.cmd` no CMD para ver a saída da compilação. O atalho grava essa saída no ficheiro local `.interbank-control.log`, ignorado pelo Git.

O arranque usa um JDK 21 ou superior. Se o Windows encontrar outra versão, posso indicar directamente a pasta do JDK em `.env.local`, por exemplo `JAVA_HOME="C:\Program Files\Java\jdk-23"`.

## Fase 1 — Preparação

- Preparei o guia de instalação e o diagrama de arquitectura. Vou mantê-los na pasta local `documentation/` até à entrega final.
- Guardei em `database/` os scripts de preparação das bases de dados.

## Fase 2 — Backends dos bancos

Criei duas aplicações Spring Boot independentes. Uso o JDK 23 instalado no Windows e o Maven Wrapper de cada banco para descarregar o Maven e as dependências. Mantive o Java 21 como alvo de compilação para que os ficheiros JAR também possam correr no Java 21. [Requisitos do Spring Boot](https://docs.spring.io/spring-boot/4.0/system-requirements.html)

No CMD do VS Code, selecciono o JDK 23 e confirmo a versão.

```bat
set "JAVA_HOME=C:\Program Files\Java\jdk-23"
set "PATH=%JAVA_HOME%\bin;%PATH%"
java -version
javac -version
```

Estas definições aplicam-se ao CMD em que as executaste. Repete-as no segundo terminal se ele ainda encontrar outra versão.

### Arrancar o Banco Kwanza

Na versão actual do projecto, preparo também as variáveis da [Fase 5](#fase-5--segurança-e-recuperação) e da [evolução para dois sentidos](#evolução--transferências-nos-dois-sentidos) antes de arrancar cada banco.
Para enviar transferências e consultar os recibos do outro banco, arranco também a [rede interbancária](#evolução--rede-interbancária).

No CMD, a partir da raiz do repositório, executa os comandos abaixo. Substitui o valor de exemplo pela palavra-passe que definiste para `interbank_kwanza`.

```bat
cd banco-kwanza
set "KWANZA_DB_PASSWORD=PALAVRA_PASSE_DO_KWANZA"
mvnw.cmd -v
mvnw.cmd spring-boot:run
```

### Arrancar o Banco Horizonte

Num segundo CMD, a partir da raiz do repositório, usa a palavra-passe de `interbank_horizonte`.

```bat
cd banco-horizonte
set "HORIZONTE_DB_PASSWORD=PALAVRA_PASSE_DO_HORIZONTE"
mvnw.cmd spring-boot:run
```

O Flyway cria as tabelas `users` e `accounts` na base de cada banco no primeiro arranque. Configurei a porta PostgreSQL `5433` por omissão. Se usares outra porta, define `INTERBANK_DB_PORT` no CMD antes de arrancar. As palavras-passe ficam nas variáveis dos terminais.

### Experimentar as APIs

Num terceiro CMD, com os servidores activos, consulta a lista do Kwanza e testa a consulta de uma conta em cada banco.

```bat
curl.exe -i http://127.0.0.1:8081/api/accounts
curl.exe -i http://127.0.0.1:8081/api/accounts/1
curl.exe -i http://127.0.0.1:8082/api/accounts/1
```

Com as bases vazias, a lista devolve `200` e `[]`. As consultas por ID devolvem `404` até existir uma conta com esse ID. Os serviços escutam apenas no próprio computador durante esta fase.

Para experimentar uma conta real da simulação, abre o Query Tool do pgAdmin em `banco_kwanza` após o primeiro arranque. Executa o exemplo uma vez.

```sql
WITH titular AS (
    INSERT INTO users (name, email, role)
    VALUES ('Cliente de Teste', 'cliente@exemplo.test', 'CUSTOMER')
    RETURNING id
)
INSERT INTO accounts (account_number, owner_id, balance, available_balance, status)
SELECT 'BK-000123', id, 100000.00, 100000.00, 'ACTIVE'
FROM titular
RETURNING id;
```

Repete a consulta a `/api/accounts` para ver a conta. Para consultar directamente pelo ID, usa o valor devolvido pelo pgAdmin. Podes repetir o exemplo em `banco_horizonte` com o número `BH-000245` e consultar a conta na porta `8082`.

## Fase 3 — Transferências entre os bancos

Implementei transferências em kwanzas (`AOA`) do Banco Kwanza para o Banco Horizonte. A migração `V2` cria as tabelas de transferências e movimentos. Quando recebo um pedido no Kwanza, reservo o montante numa transacção local. Depois envio o pedido ao Horizonte por HTTP. Quando o Horizonte confirma o crédito, finalizo o débito no Kwanza. Uma recusa definitiva liberta a reserva. Se o resultado for incerto, a transferência continua `PENDING` e posso reenviar o pedido com a mesma chave.

### Testar no Postman

Para testar os endereços locais, uso a aplicação Postman para Windows ou selecciono o **Desktop Agent** no rodapé do Postman Web. [Guia dos agentes do Postman](https://learning.postman.com/docs/getting-started/basics/about-postman-agent/)

Na versão actual, uso também o Bearer Token descrito na [Fase 5](#fase-5--segurança-e-recuperação).

1. Arranca os dois backends no CMD como na Fase 2. No pgAdmin, confirma que tens uma conta activa com saldo no Kwanza e outra activa no Horizonte. Podes usar os exemplos da Fase 2. Usa o **ID** da conta de origem e o **número** da conta de destino.
2. No Postman, cria um pedido **POST** para `http://127.0.0.1:8081/api/transfers`. Em **Headers**, adiciona `Idempotency-Key` com um UUID. Podes usar `{{$guid}}` e depois guardar o ID devolvido, ou usar `e3722ec5-3117-4b1e-a824-79578bbdff31` no primeiro teste. Em **Body → raw → JSON**, envia:

   ```json
   {
     "sourceAccountId": 1,
     "destinationAccount": "BH-000245",
     "amount": 25000.00
   }
   ```

3. Confirma `200 OK` com `COMPLETED`. O saldo do Kwanza diminui e o do Horizonte aumenta pelo mesmo montante. Reenvia o mesmo pedido com o UUID devolvido no primeiro teste. Os saldos devem continuar iguais aos posteriores à transferência. Reutilizar esse UUID com um montante diferente devolve `409 Conflict`.
4. Consulta `GET /api/transfers` ou `GET /api/transfers/{id}` no Kwanza. O recibo do cliente está em `GET /api/transfers/{id}/receipt`. O Kwanza usa a API interna `GET /api/interbank/credits/{id}` do Horizonte para obter esse recibo.

| Cenário | Resultado |
| --- | --- |
| Conta de origem inexistente | `404` |
| Saldo disponível insuficiente ou conta de origem inactiva | `422` |
| Conta de destino inexistente ou inactiva | `200` com `FAILED` e reserva libertada |
| Horizonte indisponível | `202` com `PENDING` e montante reservado. Depois de o Horizonte voltar, reenvia o mesmo corpo e UUID. |

Abro o Swagger UI em [Kwanza](http://127.0.0.1:8081/swagger-ui/index.html) e [Horizonte](http://127.0.0.1:8082/swagger-ui/index.html). Cada banco publica o documento OpenAPI em `/v3/api-docs`, que posso importar no Postman.

## Fase 4 — Interface Angular

Criei o painel do Banco Kwanza para consultar saldos, iniciar transferências e acompanhar o histórico. No painel do Banco Horizonte, consulto o recibo pela referência da transferência. Se o envio ficar pendente ou a ligação cair, posso retomá-lo no mesmo separador. A interface conserva a chave original para evitar outro débito.

Uso Angular 21 com o Node.js 24 já instalado. O Angular CLI é uma dependência do projecto. Abro um terceiro CMD no VS Code, com os dois bancos a correr conforme a Fase 2, e executo a partir da raiz:

```bat
node --version
npm --version
cd frontend\angular-app
npm ci
npm start
```

Abro [InterBank Angola](http://127.0.0.1:4200/) no navegador. A instalação com `npm ci` usa as versões do `package-lock.json`. O comando `npm start` lança o servidor de desenvolvimento. O ficheiro `src/proxy.conf.json` encaminha os pedidos do Angular para a API do banco seleccionado. O banco de origem verifica se a transferência pertence ao cliente antes de consultar o recibo no banco de destino. Uso `npm run build` para verificar a compilação.

### Experimentar no navegador

1. Em **Contas**, confirmo a conta de origem `BK-000123` e o saldo disponível.
2. Em **Histórico**, abro o recibo da transferência de teste para ver o crédito no Horizonte.
3. Em **Transferências**, escolho a conta de origem e introduzo `BH-000245` e um montante dentro do saldo disponível. Cada novo envio confirmado altera os saldos da simulação. Se o pedido ficar pendente, uso **Retomar pedido**.

## Fase 5 — Segurança e recuperação

Protegi as contas, transferências e recibos com início de sessão no Kwanza. O Angular envia um JWT de cliente válido por 30 minutos. O Kwanza limita as consultas e os débitos às contas do titular autenticado. Nas chamadas ao Horizonte, usa uma credencial interna própria via HTTP Basic. Os dois serviços escutam no endereço local para esta demonstração.

### Preparar os terminais CMD

Gero dois valores aleatórios executando duas vezes este comando. Guardo cada valor localmente para o usar nos terminais.

```bat
node -p "require('node:crypto').randomBytes(32).toString('hex')"
```

No **CMD do Kwanza**, defino as variáveis abaixo além de `KWANZA_DB_PASSWORD`. Uso um valor gerado para a assinatura JWT e o outro para a comunicação entre os bancos.

```bat
set "KWANZA_JWT_SECRET=VALOR_GERADO_PARA_O_JWT"
set "INTERBANK_SHARED_PASSWORD=VALOR_GERADO_PARA_OS_BANCOS"
```

No **CMD do Horizonte**, defino o mesmo valor interno que usei no Kwanza, além de `HORIZONTE_DB_PASSWORD`.

```bat
set "INTERBANK_SHARED_PASSWORD=VALOR_GERADO_PARA_OS_BANCOS"
```

Depois de definir as variáveis, reinicio os dois servidores com `mvnw.cmd spring-boot:run`. Se mudei o proxy do Angular, reinicio também o `npm start`.

### Activar o cliente fictício

No pgAdmin, abro o **Query Tool de `banco_kwanza`** como administrador. Se criei a conta `BK-000123` com o email da Fase 2, defino uma palavra-passe de teste para esse cliente. Substituo o marcador antes de executar. O PostgreSQL guarda o hash BCrypt. Uso uma palavra-passe exclusiva da simulação.

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;

UPDATE public.users
SET password_hash = crypt('PALAVRA_PASSE_DE_TESTE', gen_salt('bf', 12))
WHERE email = 'cliente@exemplo.test' AND role = 'CUSTOMER'
RETURNING id, email;
```

Se o `UPDATE` devolver uma linha, entro no Angular com esse email e a palavra-passe escolhida.

### Verificar autorização e recuperação

No Postman, faço `POST http://127.0.0.1:8081/api/auth/login` com **Body → raw → JSON**.

```json
{
  "email": "cliente@exemplo.test",
  "password": "PALAVRA_PASSE_DE_TESTE"
}
```

Copio o campo `token` da resposta para **Authorization → Bearer Token** nos pedidos de contas e transferências. Para um recibo do cliente, uso `GET /api/transfers/{id}/receipt` no Kwanza. Sem token, essas rotas devolvem `401`. Uma conta ou transferência pertencente a outro cliente devolve `404`.

Para simular uma falha, paro o Horizonte e inicio uma nova transferência no Angular. Ela deve ficar pendente com o montante reservado. Depois de voltar a arrancar o Horizonte com a credencial interna, o Kwanza reenvia automaticamente o pedido com a referência original. A verificação corre em ciclos de 15 segundos por omissão. Uso **Actualizar dados** para confirmar o estado e os saldos no Angular.

## Evolução — Transferências nos dois sentidos

Os dois bancos têm os mesmos serviços para clientes. Posso iniciar sessão em qualquer um, consultar contas, ver movimentos de débito e crédito, enviar ao outro banco e acompanhar o histórico e os recibos. Cada banco valida as suas sessões, guarda os seus dados e recupera os seus pedidos pendentes.

### Actualizar o ambiente existente

Mantenho `INTERBANK_SHARED_PASSWORD` nos dois CMD para o sentido Kwanza → Horizonte. Gero agora **dois valores novos** com o comando Node.js da Fase 5. Defino o primeiro nos **dois CMD**, com exactamente o mesmo valor:

```bat
set "HORIZONTE_INTERBANK_PASSWORD=VALOR_GERADO_PARA_HORIZONTE_ENVIAR"
```

Defino o segundo **apenas no CMD do Horizonte**. O Kwanza continua a usar o seu próprio `KWANZA_JWT_SECRET`.

```bat
set "HORIZONTE_JWT_SECRET=VALOR_GERADO_PARA_O_JWT_DO_HORIZONTE"
```

Reinicio os dois backends. O Flyway aplica a `V4` no Kwanza e a `V3` no Horizonte. Estas migrações acrescentam o sentido inverso e preservam as contas, os saldos e os movimentos anteriores. Reinicio também o `npm start` para carregar o proxy actualizado.

### Activar e testar o cliente Horizonte

No pgAdmin, abro o **Query Tool da base `banco_horizonte`** como administrador. Se usei o email abaixo para criar a conta `BH-000245`, defino uma palavra-passe de teste para esse cliente. Ajusto o email se tiver usado outro.

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;

UPDATE public.users
SET password_hash = crypt('PALAVRA_PASSE_DE_TESTE', gen_salt('bf', 12))
WHERE email = 'cliente.horizonte@exemplo.test' AND role = 'CUSTOMER'
RETURNING id, email;
```

No Angular, termino a sessão anterior, escolho **Banco Horizonte** e entro com o cliente que acabei de activar. Envio um montante fictício para `BK-000123`. Em **Contas → Ver movimentos**, confirmo o débito no Horizonte. Depois entro no **Banco Kwanza** para verificar o crédito. O Postman também pode testar o fluxo inverso na porta `8082` com um token emitido pelo Horizonte. A API de movimentos dos dois bancos é `GET /api/accounts/{id}/transactions`.

## Evolução — Rede interbancária

Os pedidos de transferência e de recibo passam pela rede interbancária fictícia na porta `8083`. A rede identifica o banco de origem pela credencial interna e encaminha o pedido para o outro banco. Cada banco continua responsável pelas suas contas, saldos e movimentos. A rede funciona sem uma base de dados própria.

```text
Angular → Banco de origem → Rede interbancária → Banco de destino
```

Num quarto CMD no VS Code, a partir da raiz do repositório, defino **os mesmos valores já configurados nos bancos**:

```bat
set "INTERBANK_SHARED_PASSWORD=VALOR_JA_USADO_PARA_KWANZA_ENVIAR"
set "HORIZONTE_INTERBANK_PASSWORD=VALOR_JA_USADO_PARA_HORIZONTE_ENVIAR"
cd rede-interbancaria
mvnw.cmd spring-boot:run
```

Abro `http://127.0.0.1:8083/api/network/health` e verifico que o serviço devolve `{"status":"UP"}`. Reinicio os bancos para passarem a usar a rede. Se alterar a porta da rede, defino `INTERBANK_ROUTER_URL` nos dois CMD dos bancos. Se alterar as portas dos bancos, defino `KWANZA_BASE_URL` e `HORIZONTE_BASE_URL` no CMD da rede.

Para verificar o fluxo na interface, envio um valor fictício entre as duas contas e procuro a mesma referência no terminal da rede. Para testar a recuperação, paro a rede, envio outro pedido e verifico que fica `PENDING`. Quando a rede volta, o banco de origem retoma o envio com a mesma referência.

## Plano de implementação

1. Preparei o ambiente, o repositório, os diagramas e as bases de dados.
2. Criei dois backends Spring Boot independentes, com entidades, repositórios e APIs de contas.
3. Implementei transferências via REST com reserva de saldo e idempotência. Documentei as APIs com OpenAPI e incluí um roteiro para experimentar no Postman.
4. Criei a aplicação Angular para consulta de contas, transferências, estados, histórico e recibos. A integração do início de sessão acompanha a segurança da Fase 5.
5. Usei Spring Security e JWT para proteger o acesso dos clientes e uma credencial interna para autenticar as chamadas ao Horizonte. Implementei a recuperação automática de operações pendentes.
6. Vou preparar a demonstração, os diagramas finais, o relatório e a apresentação.

Depois da primeira entrega, vou implementar por etapas as evoluções do projecto. Planeio adicionar Spring Cloud Gateway, Docker Compose, RabbitMQ, notificações e transferências por número de telemóvel. Também vou criar um serviço central fictício de interoperabilidade, separar os módulos em quatro microserviços, adicionar observabilidade e integrar outros bancos fictícios.
