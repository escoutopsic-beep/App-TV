# FrameXTV TV Pro para TCL Google TV (v0.7.5)

Cliente leve do FrameXTV para TVs TCL / Google TV (2 GB de RAM, 4K/60 Hz).

## Como gerar o APK
1. Cada push na branch `main` dispara o workflow **Build FrameXTV TCL APK** (aba **Actions**).
2. Ao terminar, baixe o artefato `FrameXTV-TCL-vX.Y.Z` (zip com o APK) na página da execução.
3. O APK é **release**: otimizado com R8 e remoção de recursos não usados.

### Assinatura fixa
O workflow usa os segredos do repositório `FXTV_KEYSTORE_B64` (keystore em base64) e
`FXTV_KEYSTORE_PASSWORD`. Com eles, toda versão nova instala por cima da anterior sem perder
favoritos. Sem eles, o APK sai com chave temporária e é preciso desinstalar antes de atualizar.

## Controles
- **D-pad**: navega entre cards, menus e campos com foco dourado.
- **OK**: abre links, botões, campos de texto (teclado) e players embutidos.
- **MENU**: menu do app (favoritos, configurações, tela cheia, cache).
- **INFO**: alterna o modo de controle do player.
- **No player**: OK = play/pausa, ◀ ▶ = -15 s / +15 s (segurar: 30 s), ▲ = tela cheia, ▼ = opções do player, VOLTAR = sair.
- **Opções do player** (▼, MENU → Opções do player, ou botões de legenda/áudio do controle): legendas, faixa de áudio, qualidade e velocidade, inclusive em players embutidos.

## Configurações
- **Qualidade do vídeo**: sempre a máxima (padrão) ou automática.
- **Escala da página** (100/90/80/75/67%): encolhe a página inteira mantendo o layout responsivo.
- **Tamanho do texto** (90/100/110/125%).
- **Bloqueio de anúncios e rastreadores**, **tela sempre ligada** e **abrir na última página**.

## Novidades da v0.7.5
- A caixa "Welcome to frameXTV" do início não aparece mais (é escondida antes de ser desenhada e fechada pelo próprio botão do site).

## Download direto
https://github.com/escoutopsic-beep/App-TV/releases/latest/download/FrameXTV-TCL.apk

## Novidades da v0.7.4
- Removida a barra "Like frameXTV? ... Buy $XTV" acima do player e o aviso "Install frameXTV".
- Pop-ups "Welcome to frameXTV" e "frameXTV has a new look" fechados automaticamente.

## Novidades da v0.7.3
- Qualidade do vídeo sempre na máxima disponível (MENU → Configurações para voltar ao automático). Uma escolha manual no menu do player é respeitada.
- Opções do player funcionam também com o vídeo ainda carregando.

## Novidades da v0.7.2 (proteção)
- Pop-ups e pop-unders bloqueados em todos os frames, inclusive dentro dos players embutidos (window.open desativado antes dos scripts do site).
- Cliques em links que saem do site (ou do domínio do player) são descartados.
- Alertas, confirmações e "Deseja sair desta página?" descartados em silêncio.
- Lista de bloqueio ampliada com redes de pop-under, push e apostas; ocultação leve de blocos de anúncio.

## Novidades da v0.7.1 (fluidez)
- Segurar o direcional não acumula mais movimentos: o foco para assim que a tecla é solta.
- Navegação mais leve em catálogos grandes (visibilidade só é checada nos melhores candidatos).
- Removidas regras de CSS de anúncios caras para a TV; o bloqueio continua na rede.
- Motor JS não é mais reenviado a cada página; página com prioridade de processamento; sem checagem extra do Navegação Segura.

## Novidades da v0.7.0
- Opções do player pelo controle: legendas, áudio, qualidade e velocidade (JW Player, Video.js, hls.js e vídeo nativo).
- Comandos do controle (play/pausa, avançar, voltar) chegam também a players embutidos de outros domínios.
- Corrigido: carrosséis e prévias da página inicial acelerados (o app não mexe mais nas animações do site).

## Novidades da v0.6.0
- Mensagem de erro de conexão volta a aparecer (antes sumia na hora) e o OK recarrega a página certa.
- Filtro de anúncios por domínio sem bloquear CDNs legítimas (ex.: `uploads.`, `downloads.`).
- OK nativo em links/campos/players: o teclado abre em campos de busca e os players embutidos respondem.
- Tela cheia passa a funcionar pelo controle (precisa de gesto real do usuário).
- Menos RAM: sem camada de hardware extra e sem pré-renderização fora da tela.
- Tela não apaga durante vídeo; vídeo pausa ao sair do app.
- Recuperação automática quando o sistema fecha a página por falta de memória.
