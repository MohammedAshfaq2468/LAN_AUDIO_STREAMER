# Publishing to GitHub and GitLab

This package is prepared for one Git repository that can be pushed to both GitHub and GitLab. Create two **empty** repositories (do not pre-create a README, license, or `.gitignore` on the hosting sites):

- GitHub repository name: `wifi-audio-speaker`
- GitLab project name: `wifi-audio-speaker`

For a job application, a public repository is usually the easiest for reviewers to open. Before setting it public, review the source again and confirm you are comfortable publishing the implementation. This curated package deliberately excludes raw device logs, local screenshots, personal contact details, signing keys, and machine-specific configuration.

## First-time push from Windows PowerShell

Extract `WiFi-Audio-Speaker-Portfolio.zip`, open PowerShell in the extracted `WiFi-Audio-Speaker-Portfolio` directory, then run:

```powershell
git init -b main
git config user.name "Mohammed Ashfaq"
# Use the verified GitHub noreply email from your GitHub account settings.
git config user.email "YOUR_VERIFIED_GITHUB_NOREPLY_EMAIL"
git add .
git status --short
git commit -m "Initial Wi-Fi Audio Speaker portfolio snapshot"
```

Review the staged file list before committing. Then add both remotes (replace the account names):

```powershell
git remote add github https://github.com/YOUR_GITHUB_USERNAME/wifi-audio-speaker.git
git remote add gitlab https://gitlab.com/YOUR_GITLAB_USERNAME/wifi-audio-speaker.git
git push -u github main
git push -u gitlab main
```

Git will use the credential manager/browser authentication configured on your computer. Never paste a personal access token into source files, chat, or the repository.

## Later updates

```powershell
git add .
git commit -m "Describe the change"
git push github main
git push gitlab main
```

If you use different branch names or protected-branch rules, follow the host's prompts. Keep GitHub as the primary portfolio link and GitLab as a mirror unless a recruiter asks otherwise.

## Play Store preparation later

A source repository is not itself a Play Store release. Before release, restore and verify the complete Android Studio project, build and test a signed Android App Bundle (`.aab`), configure application ID/versioning and signing securely, complete Play Console app content and data-safety declarations, and run a release track test. Never upload keystore files or signing passwords to GitHub/GitLab.
