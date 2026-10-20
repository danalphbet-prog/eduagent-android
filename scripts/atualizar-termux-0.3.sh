#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

# Execute no Termux com o GitHub CLI já autenticado.
# Baixe o ZIP desta versão em /sdcard/Download antes de executar.
EDU_ZIP="${1:-/sdcard/Download/eduagent-memory-0.3.0-source.zip}"
EDU_REPO="danalphbet-prog/eduagent-android"

if [[ ! -f "$EDU_ZIP" ]]; then
  echo "ZIP não encontrado: $EDU_ZIP"
  exit 1
fi
for tool in git gh unzip; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "Instale a ferramenta ausente no Termux: $tool"
    exit 1
  fi
done
gh auth status >/dev/null

EDU_WORK="$(mktemp -d)"
echo "Pasta de atualização: $EDU_WORK"
gh repo clone "$EDU_REPO" "$EDU_WORK/repo" -- --branch main
mkdir -p "$EDU_WORK/zip"
unzip -q "$EDU_ZIP" -d "$EDU_WORK/zip"
if [[ ! -f "$EDU_WORK/zip/eduagent-android/app/build.gradle.kts" ]]; then
  echo "ZIP inválido: não contém a pasta eduagent-android"
  exit 1
fi
cp -a "$EDU_WORK/zip/eduagent-android/." "$EDU_WORK/repo/"
cd "$EDU_WORK/repo"
git add -A
if git diff --cached --quiet; then
  echo "Nenhuma diferença: a versão já pode estar atualizada."
else
  git commit -m "EduAgent 0.3: memória profunda, checklist e centralização do composer"
  git push origin main
fi
echo "Verifique o APK atualizado na aba Actions do repositório:"
gh run list --repo "$EDU_REPO" --limit 5
