# prontopop requirements

A single-page, server-less metronome app for live performance of songs, in Scala.js + Laminar.

## Goals

* Stakeholder: performer has
  * Goal: metronomeLivePerformance
  * Goal: instant
  * Goal: localOwnData
* Stakeholder: developer has
  * Goal: minimalDependencies
* Goal: metronomeLivePerformance has
  * Gist: metronome for live performance of songs
* Goal: instant has
  * Gist: the page loads instantly with no backend to wait for
* Goal: localOwnData has
  * Gist: concerts are saved in the browser only; no accounts, no server, no tracking
* Goal: minimalDependencies has
  * Gist: only Laminar and scalajs-dom besides the Scala and JS standard libraries
  * Comment: handroll a small facade if a needed browser API is missing from scalajs-dom

## Features

* Feature: songTable has
  * Gist: editable list of songs, each row with play toggle, title, bpm, signature and pattern, plus add and remove
  * Spec: a song has title, bpm, time signature and pattern; signature is a field of Song and the parser validates the pattern against it
* Feature: patternDsl has
  * Gist: compact one-line notation for the click pattern of a song
  * Spec: one character per beat where '!' is an accented click, 'X' a normal click, '.' a soft click with lower velocity than 'X', '_' a silent beat, '|' a bar separator, and '||:' with ':||' loops the whole pattern forever; characters per bar must equal the signature numerator; parse errors carry message and position
  * Example: ||:!..|X..|X..|X..:|| is four looping bars of 3/4 with an accent on beat one and soft clicks in between
* Feature: playback has
  * Gist: per-song play toggle that clicks the pattern at the song's bpm until stopped
  * Spec: clicks are synthesized with WebAudio; an accent is higher pitched and louder; at most one song plays at a time
  * Why: AudioContext scheduling gives precise live timing with zero extra dependencies
* Feature: concertStore has
  * Gist: save and load a named concert, with its whole song list, in the browser's local storage
* Feature: monospaceUi has
  * Gist: one monospace-styled landing page, responsive from desktop to mobile

## Traceability

* Feature: playback helps Goal: metronomeLivePerformance
* Feature: patternDsl helps Goal: metronomeLivePerformance
* Feature: songTable helps Goal: metronomeLivePerformance
* Feature: concertStore helps Goal: localOwnData
* Feature: playback requires Feature: patternDsl
* Feature: concertStore requires Feature: songTable
* Target: staticOnly has
  * Gist: the deployed app is static files that make no network requests after load
* Target: staticOnly verifies Goal: instant
