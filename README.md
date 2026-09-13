# 🎸 Academia do Violão

**Aprenda. Pratique. Toque de ouvido.**

Aplicativo Android para estudo e prática de violão. O projeto reúne mapa tonal, campo harmônico, progressões, diagramas de acordes, pesquisa de músicas, afinador cromático, metrônomo e recursos de apoio ao estudo em uma única interface.

## 📱 O que o aplicativo faz

### Mapa Tonal
- Exibe campos harmônicos maiores e menores.
- Mostra funções harmônicas e acordes relativos.
- Apresenta progressões musicais e variações de condução.
- Permite tocar em um acorde para visualizar sua montagem no braço do violão.
- Exibe diagramas com casas, dedos, cordas soltas, cordas não tocadas e diferentes posições.

### Pesquisa de músicas
- Pesquisa músicas e artistas.
- Consulta informações de músicas no Deezer.
- Busca a tonalidade da música no Cifra Club.
- Abre a letra pela página original do Letras.mus.br dentro do aplicativo.
- Atualiza o Mapa Tonal automaticamente conforme a tonalidade encontrada.
- Permite corrigir manualmente a tonalidade.
- Possui pesquisa por voz em português.
- Permite adicionar músicas aos favoritos.

### Afinador
- Afinador cromático utilizando o microfone do dispositivo.
- Identificação da nota e frequência captada.
- Exibição de diferença em cents.
- Considera a nota afinada entre **-5 e +5 cents**.
- Apresenta acidentes sustenidos por equivalentes em bemol quando aplicável.

### Metrônomo
- Controle de BPM.
- Reprodução e pausa.
- Integração com o afinador para evitar uso simultâneo do microfone.
- Quando uma música possui BPM disponível, o aplicativo pode iniciar o metrônomo diretamente no andamento informado.

### Perfil
- Perfil local por e-mail.
- Histórico de uso.
- Repertório e favoritos separados por perfil.

## 🛠 Tecnologias

- **Kotlin**
- **Android Studio**
- **Android SDK 35**
- **JDK 17**
- **Gradle / Android Gradle Plugin 8.7.3**
- **WebView + HTML/CSS/JavaScript** para a interface do aplicativo
- APIs e páginas externas utilizadas para consulta de informações musicais

## ✅ Requisitos

Antes de instalar o projeto, tenha:

- Android Studio atualizado.
- JDK 17.
- Android SDK 35 instalado.
- Conexão com a internet para as funções de pesquisa.
- Dispositivo ou emulador Android com **API 23 ou superior**.
- Permissão de microfone para afinador e pesquisa por voz.

## 🚀 Como instalar e executar

### 1. Clone o repositório

```bash
git clone https://github.com/thaisgscruz/academia-do-violao.git
```

Entre na pasta:

```bash
cd academia-do-violao
```

### 2. Abra no Android Studio

1. Abra o **Android Studio**.
2. Clique em **Open**.
3. Selecione a pasta do projeto.
4. Aguarde a sincronização do Gradle.

### 3. Configure o ambiente

Confirme no Android Studio:

- **JDK:** 17
- **Compile SDK:** 35
- **Target SDK:** 35
- **Min SDK:** 23

### 4. Execute o aplicativo

1. Conecte um celular Android com depuração USB habilitada ou crie um emulador.
2. Selecione o dispositivo no Android Studio.
3. Clique em **Run ▶**.

O projeto foi testado com configuração equivalente a um **Pixel 6 / Android API 35**.

## 📦 Gerar APK

Pelo Android Studio:

**Build → Build App Bundles or APKs → Build APKs**

O APK de debug será gerado normalmente em:

```text
app/build/outputs/apk/debug/app-debug.apk
```

> O arquivo `build-apk.sh` usa `./gradlew assembleDebug`. Caso o Gradle Wrapper ainda não esteja presente no projeto, gere-o pelo Android Studio/Gradle antes de utilizar esse script.

## 🔐 Permissões utilizadas

O aplicativo solicita:

- `INTERNET` — consultas e integração com serviços externos.
- `RECORD_AUDIO` — afinador e pesquisa por voz.

## 📚 Fontes externas e direitos autorais

A tonalidade é consultada no **Cifra Club**.

As letras não são copiadas nem armazenadas pelo aplicativo. Quando disponíveis, são abertas a partir da página original do **Letras.mus.br**.

O aplicativo também utiliza informações públicas de músicas disponibilizadas pelo **Deezer**.

## 📌 Versão

**1.7.0**

Principais recursos desta versão:

- Metrônomo integrado ao BPM da música.
- Sincronização automática da tonalidade da música com o Mapa Tonal.
- Correção manual de tonalidade.
- Afinador com tolerância de ±5 cents.
- Mapa Tonal mais compacto.
- Diagramas interativos de acordes.
- Novo slogan: **“Aprenda. Pratique. Toque de ouvido.”**

## 👩‍💻 Projeto

Desenvolvido como aplicativo Android para estudo musical e prática de violão.
