# Known limitations

- **Two sign-ins.** Label Studio Community Edition has no SSO: after signing in with Box, annotators also log in to each dataset's Label Studio, behind the gate.
- **Task locks are advisory.** The patched image makes the Data Manager queue skip locked tasks ([distribution.md](spikes/label-studio/distribution.md)), but Label Studio does not check locks on submit. Two people can still label one task; the server keeps every annotation (latest wins) and the report counts duplicates.
- **Membership lag.** Box access is checked at sign-in. Someone removed from a dataset's Box folder keeps its Label Studio (every clip on disk and every label) until their session ends, at most 8 h. Accepted; faster revocation: #146.
- **Clips are temporary.** They are deleted once a video is fully labeled; they can be regenerated from Box (#144).
- **One Box app owner.** The Box OAuth app belongs to one USF account. If that account ends, sign-in and processing stop until the app is handed over (#143).
- **Box API calls are billed to USF's Box plan.** Volume is small: folder listings and ranged downloads.
