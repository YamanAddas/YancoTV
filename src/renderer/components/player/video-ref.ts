/**
 * Module-level ref for the HTML5 video element.
 * Allows the player store and keyboard shortcuts to control
 * the video without passing refs through React context.
 */
let videoElement: HTMLVideoElement | null = null;

export function setVideoElement(el: HTMLVideoElement | null): void {
  videoElement = el;
}

export function getVideoElement(): HTMLVideoElement | null {
  return videoElement;
}
