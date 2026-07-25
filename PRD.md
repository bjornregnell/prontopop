# prontopop requirements

A single-page, server-less metronome app for live performance of songs, in Scala.js + Laminar.

## Goals

* Stakeholder: performer has
  * Goal: metronomeLivePerformance
  * Goal: instant
  * Goal: localOwnData
  * Goal: beautifulStyle
  * Goal: easyStart
* Stakeholder: developer has
  * Goal: minimalDependencies
* Goal: metronomeLivePerformance has
  * Gist: metronome for live performance of songs
* Goal: instant has
  * Gist: the page loads instantly with no backend to wait for
* Goal: localOwnData has
  * Gist: concerts are saved in the browser only; no accounts, no server, no tracking
* Goal: beautifulStyle has
  * Gist: the app is pleasant to look at and easy to read, on a dark stage as well as in a lit room
  * Why: a metronome is stared at throughout a gig, so the surface must suit the venue's light
* Goal: easyStart has
  * Gist: a first-time visitor meets a working concert and can press play at once, without first learning the pattern notation
  * Why: the app has to earn the first minute before anyone will trust it on a stage
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
* Feature: builtInConcerts has
  * Gist: example concerts that ship inside the app, offered for loading beside the ones saved in the browser
  * Spec: the song table opens on an example concert; the saved-concerts dropdown lists the built-in titles too, except where a saved concert already uses that title, in which case the saved one wins
  * Why: an empty table teaches nothing, and a visitor should hear a click before being asked to write a pattern
* Feature: themeSelector has
  * Gist: a dropdown at the top right that switches the whole page between light and dark themes
  * Spec: Automatic follows the operating system's light or dark setting; Forgy dark, Smither light, Calm dark and Calm light are explicit picks; the choice persists in local storage
  * Comment: palette, themes and Fira fonts follow genscalator's design language and are reused from the same origin

## Traceability

* Feature: playback helps Goal: metronomeLivePerformance
* Feature: patternDsl helps Goal: metronomeLivePerformance
* Feature: songTable helps Goal: metronomeLivePerformance
* Feature: concertStore helps Goal: localOwnData
* Feature: themeSelector helps Goal: beautifulStyle
* Feature: monospaceUi helps Goal: beautifulStyle
* Feature: builtInConcerts helps Goal: easyStart
* Feature: patternDsl hurts Goal: easyStart
* Feature: builtInConcerts requires Feature: concertStore
* Feature: playback requires Feature: patternDsl
* Feature: concertStore requires Feature: songTable
* Target: staticOnly has
  * Gist: the deployed app is static files that make no network requests after load
* Target: staticOnly verifies Goal: instant
