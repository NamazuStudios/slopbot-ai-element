import { SlopbotAssetsPlugin } from './SlopbotAssetsPlugin'

declare const window: Window & {
  __elementsPlugins?: {
    register(route: string, component: unknown): void
  }
}

window.__elementsPlugins?.register('slopbot-ai-assets', SlopbotAssetsPlugin)
