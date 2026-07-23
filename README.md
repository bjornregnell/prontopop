# prontopop

A single-page, server-less web app in Scala.js + Laminar, built with scala-cli.

## Build

```bash
scala-cli --power package --js . -o main.js --force
```

## Run

Serve the directory statically and open it, e.g.:

```bash
tt serv .
```

then open <http://127.0.0.1:8000> — or just open `index.html` directly in a browser.

## Stack

- Scala 3.9.0-RC1 (Scala.js 1.22.0) via scala-cli
- [Laminar](https://laminar.dev) 17.2.1 for the reactive UI
- No server: the app is static files (`index.html` + compiled `main.js`)

## Mirrors

- <https://github.com/bjornregnell/prontopop>
- <https://git.cs.lth.se/bjornregnell/prontopop>
