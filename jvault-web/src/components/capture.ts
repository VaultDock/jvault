/**
 * Getting a screenshot into the form.
 *
 * <p>Two ways, because people already have habits. Pasting is the one they have — every bug
 * report in the world starts with a screenshot on the clipboard — and capture is for when the
 * thing worth showing is on screen right now.
 *
 * <p>A captured image is an attachment like any other: encrypted before it reaches a disk, its
 * generated name encrypted with it, and Jira told a link. Which is worth knowing, because a
 * screenshot of a payroll screen is precisely the kind of thing people paste into tickets
 * without thinking.
 */

/** Whether this browser can capture the screen at all. Secure contexts only, so not every setup. */
export function canCapture(): boolean {
  return typeof navigator !== 'undefined'
    && navigator.mediaDevices !== undefined
    && typeof navigator.mediaDevices.getDisplayMedia === 'function';
}

/**
 * Asks the browser to capture a screen, window or tab, and returns one frame as a PNG.
 *
 * <p>What gets captured is the browser's business, not this function's: it shows its own picker
 * and this code receives whatever the person chose. There is no way to ask for a particular
 * window, which is the correct arrangement.
 */
export async function captureScreenshot(): Promise<File> {
  const stream = await navigator.mediaDevices.getDisplayMedia({
    video: true,
    audio: false,
    // A cursor in a screenshot of an error message is noise.
    // @ts-expect-error not in every lib.dom yet, ignored where unsupported
    preferCurrentTab: false,
  });

  try {
    const blob = await frameFrom(stream);
    return new File([blob], screenshotName(), { type: 'image/png' });
  } finally {
    // Without this the browser keeps showing "sharing your screen" until the tab closes, which
    // is alarming and entirely our fault.
    stream.getTracks().forEach((track) => track.stop());
  }
}

async function frameFrom(stream: MediaStream): Promise<Blob> {
  const video = document.createElement('video');
  video.srcObject = stream;
  video.muted = true;
  await video.play();

  // One frame, once there is one: reading immediately after play() gives a blank canvas.
  await new Promise((resolve) => requestAnimationFrame(resolve));

  const canvas = document.createElement('canvas');
  canvas.width = video.videoWidth;
  canvas.height = video.videoHeight;

  const context = canvas.getContext('2d');
  if (!context || canvas.width === 0) {
    throw new Error('the capture produced no image');
  }
  context.drawImage(video, 0, 0);
  video.pause();
  video.srcObject = null;

  return new Promise<Blob>((resolve, reject) => {
    canvas.toBlob(
      (blob) => (blob ? resolve(blob) : reject(new Error('the capture could not be encoded'))),
      'image/png',
    );
  });
}

/** The images on a paste, if any. A pasted screenshot arrives with no name of its own. */
export function imagesFromPaste(clipboard: DataTransfer | null): File[] {
  if (!clipboard) {
    return [];
  }
  const images: File[] = [];
  for (const item of Array.from(clipboard.items)) {
    if (item.kind !== 'file' || !item.type.startsWith('image/')) {
      continue;
    }
    const file = item.getAsFile();
    if (!file) {
      continue;
    }
    // Chrome calls every pasted image "image.png", so three pastes would look like one file to
    // anything deduplicating by name.
    images.push(
      file.name && file.name !== 'image.png'
        ? file
        : new File([file], screenshotName(extensionOf(file.type)), { type: file.type }),
    );
  }
  return images;
}

/**
 * A name that says what it is and when it was taken.
 *
 * <p>Local time rather than UTC: the person reading it later is the person who took it, and
 * "screenshot-2026-09-13T16-42-05" should match the clock they were looking at.
 */
function screenshotName(extension = 'png'): string {
  const now = new Date();
  const stamp = [
    now.getFullYear(),
    pad(now.getMonth() + 1),
    pad(now.getDate()),
  ].join('-') + 'T' + [pad(now.getHours()), pad(now.getMinutes()), pad(now.getSeconds())].join('-');
  return `screenshot-${stamp}.${extension}`;
}

function pad(value: number): string {
  return String(value).padStart(2, '0');
}

function extensionOf(mediaType: string): string {
  const subtype = mediaType.split('/')[1] ?? 'png';
  return subtype === 'jpeg' ? 'jpg' : subtype.replace(/\+.*$/, '');
}
