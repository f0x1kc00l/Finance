# Replacing stale files on GitHub

The supplied source tree uses `PinStoreImpl` and `PinFlowViewModel`. It does not contain the old classes `PinRepositoryImpl`, `PinDataStore`, or `PinViewModel` referenced by the reported GitHub Actions log. This suggests that the GitHub repository still has files from an older version.

## Safe replacement procedure

1. Back up your current project folder.
2. Clone the GitHub repository into a NEW folder (do not run `git init` in the ZIP folder):
   `git clone https://github.com/f0x1kc00l/FinanceApp.git FinanceApp-sync`
3. Extract this archive into a separate temporary folder.
4. In `FinanceApp-sync`, remove the old project files and folders, but KEEP the hidden `.git` folder. Do not delete `.git`.
5. Copy the contents of the extracted `FinanceApp-main` folder into `FinanceApp-sync`, including hidden files/folders such as `.github`.
6. In PowerShell, from `FinanceApp-sync`, run:
   `git status`
   Confirm that old files are shown as deleted and current files as added/modified.
7. Commit and push the complete replacement:
   `git add -A`
   `git commit -m "Replace stale project files with FinanceApp 2.0"`
   `git push origin main`
8. Open GitHub Actions and rerun/check the latest workflow.

Do not use `git push --force`. This archive is based on the provided source tree and has not been verified by a successful Gradle build in this environment. If the build still fails after replacing stale files, inspect the new log because it may show a separate issue.
