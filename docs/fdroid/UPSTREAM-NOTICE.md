# Heads-up to emon (Sterna Mail upstream)

F-Droid's App Inclusion template asks that the original app author be notified. Sterna Mail is
already in F-Droid as `app.sterna`, so this is worth doing properly rather than skipping.

Channel: an issue at <https://codeberg.org/emon/sterna-mail/issues> (needs a Codeberg account).
Title: **Heads-up: a GPLv3 fork (Gridlink Mail) is being submitted to F-Droid**

---

Hi emon,

Not a bug report, just a courtesy heads-up so this doesn't reach you sideways.

I've built a fork of Sterna Mail called **Gridlink Mail** (`app.gridlink`,
<https://github.com/tatelink/gridlink-mail>) and I've submitted it to F-Droid
(fdroid/fdroiddata!46061). It stays GPL-3.0-only, and the README leads with a fork notice
crediting you: the mail engine, the sync layer, the OpenPGP support and most of the settings are
your work. What the fork changes is the front end and the setup flow, plus CalDAV/CardDAV in the
same app.

It ships under its own name, its own application ID and its own icon, so it can't be mistaken for
a Sterna release, and the README says plainly that it isn't one and that you don't support it. It
also points anyone who wants the real thing back at your repo, which is the better-tested and
actively maintained app.

I'm not asking you for anything and there's nothing you need to do. If you'd rather the
attribution were worded differently, or you'd like something changed about how the fork presents
itself, tell me and I'll change it.

Thanks for building Sterna and for licensing it the way you did.

---

Once posted, paste the issue link into the F-Droid MR and tick the "original app author has been
notified" box.
