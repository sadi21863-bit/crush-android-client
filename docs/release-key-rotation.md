# Release credential rotation runbook

Status: **standing checklist, last executed 2026-11-14** (the rotation itself).
Updated 2026-11-22 with the local scanner. This file records procedure and
status only - **no key material, partial key, or provider console URL with a
token belongs here.**

## 1. What happened

`opencode api key for testing.txt` sat at the repo root until 2026-11-14. It
was gitignored (`*key*.txt`), so it was never committed; deletion alone would
not have remediated the exposure. The owner rotated the OpenCode Zen key at
the provider, pasted the replacement directly into Settings, verified a turn,
revoked the old value and deleted the file. Closure: `docs/decisions-open.md`
§3, `docs/backlog-research-2026-11-13.md` §A1.

The Groq key was also exposed once in a tool transcript (an
`Invoke-RestMethod` printed the workspace JSON); it was rotated on 2026-10-25
(`project-analysis.md` §9) and Groq stays parked as a test target.

## 2. When to run this

- Before any build that leaves the owner's hands.
- After any tool output, log, screenshot or export that might contain a key.
- When switching test providers or reviving a parked one.

## 3. Runbook (owner actions marked **[owner]**)

1. **[owner]** Revoke the exposed value at the provider console. Revoke first;
   do not create the replacement in the same session if the console allows a
   separate revoke.
2. **[owner]** Create a replacement with the narrowest scope and an expiry.
3. **[owner]** Paste it directly into Settings on the device. The agent never
   sees, prints or stores the value; do not paste it into chat, a shell
   command, a file or an issue.
4. **[owner]** Send one real turn and confirm it completes.
5. **[owner]** Confirm the revoked value now fails (401) from the provider's
   own check. Do not test it by printing it into a transcript; use the
   provider's revocation status where available.
6. Delete local copies: repo root `*.txt`, Downloads, clipboard managers,
   any `/sdcard` fallback (the old `zenkey.txt` fallback is already removed
   from the spike harness).
7. Run the scanner from the repo root:

   ```powershell
   pwsh -File tools/check-secrets.ps1
   ```

   It must print `PASS`. The two allowlisted hits are synthetic fixtures in
   `KeyMaskTest` and `AppLogTest`, pinned by SHA-256.
8. Record only the date and the provider in `docs/decisions-open.md` §3. No
   key material, no prefix, no length.

## 4. Standing rules

- Never print a key, even masked, in a command that the shell echoes back.
  Mask config JSON with `[A-Za-z0-9_\-]{25,}` -> `<masked>` before display.
- Never `run-as ... cat` a credential file or `secure_api_keys.xml`.
- Provider keys live in the AES/GCM store (`SecureKeyStore`); the engine's
  `crush.json` is plaintext by upstream design and is app-private.
- A scanner PASS is not proof of no exposure: screenshots, transcripts and
  provider dashboards are outside its scope.

## 5. Current state (2026-11-22)

| Item | State |
|---|---|
| Repo-root key file | removed 2026-11-14; was never tracked |
| OpenCode Zen key | rotated 2026-11-14; closure recorded |
| Groq key | exposed once in a transcript; rotated 2026-10-25; Groq stays parked |
| Tracked full-key shapes | none; two synthetic fixtures allowlisted |
| Device keys | `opencode-go` and `openrouter` stored; never read into chat or logs |
| History | the repo-root file was gitignored, so no commit should contain it; a blob-by-blob history scan was not run |
| Local scanner | `tools/check-secrets.ps1`, PASS on 2026-11-22 |
