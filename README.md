# FrameXTV TV Pro para TCL Google TV (v0.6.0)

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
- **No player**: OK = play/pausa, ◀ ▶ = -15 s / +15 s (segurar: 30 s), ▲ = tela cheia, VOLTAR = sair.

## Configurações
- **Escala da página** (100/90/80/75/67%): encolhe a página inteira mantendo o layout responsivo.
- **Tamanho do texto** (90/100/110/125%).
- **Bloqueio de anúncios e rastreadores**, **tela sempre ligada** e **abrir na última página**.

## Novidades da v0.6.0
- Mensagem de erro de conexão volta a aparecer (antes sumia na hora) e o OK recarrega a página certa.
- Filtro de anúncios por domínio sem bloquear CDNs legítimas (ex.: `uploads.`, `downloads.`).
- OK nativo em links/campos/players: o teclado abre em campos de busca e os players embutidos respondem.
- Tela cheia passa a funcionar pelo controle (precisa de gesto real do usuário).
- Menos RAM: sem camada de hardware extra e sem pré-renderização fora da tela.
- Tela não apaga durante vídeo; vídeo pausa ao sair do app.
- Recuperação automática quando o sistema fecha a página por falta de memória.
