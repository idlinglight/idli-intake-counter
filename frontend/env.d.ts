/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Full git sha, passed as a build-arg when the image is built; unset in dev. */
  readonly VITE_GIT_SHA?: string
}
