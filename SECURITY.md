# Security Policy

## Scope

VulnScope is a learning and engineering practice project. It is not a managed vulnerability scanner and should not be used as the sole basis for production security decisions.

## Reporting

Please open a GitHub issue that describes the affected component and a minimal, non-destructive reproduction. Do not include API keys, passwords, private datasets, personal information, or details that would expose a live system.

## Secret handling

- Keep runtime values in ignored `.env` files or external secret stores.
- Never expose model keys through `VITE_*` variables.
- Keep remote Spark submission disabled unless the deployment environment is explicitly configured.
- Rotate any credential that was accidentally committed before publishing a fix.
