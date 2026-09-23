# Localiza Família

App de localização em tempo real para a família: mapa, chat, tarefas, cercas de aviso, SOS, histórico de rota e assistente de voz.

**O app já é white-label:** dá para entregar para outra família trocando só a configuração.

## Para a família atual (rápido)

O site fica em GitHub Pages e o APK é gerado automaticamente em cada build.

## Como passar para outra família

Cada família usa **o próprio projeto Firebase** (os dados ficam separados de graça). Passos:

1. **Criar o projeto no Firebase** (console.firebase.google.com → *Add project*).
2. No projeto novo:
   - **Authentication → Sign-in method → Anonymous** → habilitar.
   - **Realtime Database → Rules** → colar o conteúdo do arquivo [`database.rules.json`](database.rules.json) → *Publish*.
3. **Editar [`config.js`](config.js)** com as chaves do projeto novo:
   ```js
   window.LF_CONFIG = {
     apiKey: "SUA_API_KEY",
     authDomain: "seu-projeto.firebaseapp.com",
     databaseURL: "https://seu-projeto-default-rtdb.firebaseio.com",
     projectId: "seu-projeto"
   };
   ```
4. **Hospedar o site** — o workflow [`.github/workflows/pages.yml`](.github/workflows/pages.yml) já publica no GitHub Pages:
   - **Settings → Pages → Source → GitHub Actions**.
5. **APK Android**: o app nativo é genérico — a página passa a URL/apiKey do banco pra ele (bridge). Para apontar o APK para o site de outra família, troque `APP_URL` em [`MainActivity.kt`](android/app/src/main/java/com/localizafamilia/app/MainActivity.kt) e o build do GitHub Actions gera o APK novo (tag `nativo-build-*`).

## Avisos de segurança

- As regras exigem **login anônimo** (`auth != null`) para ler e escrever — sem isso ninguém lê a posição da família.
- A senha da família trava a tela do app. Para fechar 100% o acesso por usuário real, o próximo passo é Firebase Auth por telefone/e-mail com regras por `auth.uid`.

## Funcionalidades

- 📍 Localização em tempo real no mapa (Leaflet + OpenStreetMap)
- 👨‍👩‍👧 Lista da família com distância, status e bateria
- 💬 Chat em família · 📋 Tarefas · 📌 Cercas de aviso (chegou/saiu)
- 🆘 SOS com alerta na tela de todos + WhatsApp para contatos de emergência
- 🎤 Assistente de voz (localizar, SOS, resumo, mensagens)
- 🌙 Modo invisível · 🔒 PIN de controle (Android)
- 📊 Histórico de rota do dia · 🔋 alerta de bateria fraca / membro parado
- 📦 APK nativo com auto-atualização e serviço de localização em segundo plano