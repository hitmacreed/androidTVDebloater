<script>
  import { w, go, exclusive, text, setWallpaper } from '../lib/wizard.svelte.js';
  import Icon from '../lib/Icon.svelte';
  import { t, fromBackend } from '../lib/i18n.js';

  // Same size the TV keeps; sending more only makes the upload slower.
  const MAX = 1920;

  let input;
  let preview = $state('');
  let photo = $state(null);
  let error = $state('');
  let sending = $state(false);

  const TV_READS = ['image/jpeg', 'image/png', 'image/webp'];

  // True when the webview drew nothing (all pixels black or transparent).
  function blank(c) {
    const t = document.createElement('canvas');
    t.width = t.height = 16;
    const g = t.getContext('2d');
    g.drawImage(c, 0, 0, 16, 16);
    const d = g.getImageData(0, 0, 16, 16).data;
    for (let i = 0; i < d.length; i += 4) if (d[i] || d[i + 1] || d[i + 2]) return false;
    return true;
  }

  /**
   * Shrinks the photo to TV size as a JPEG (the webview also applies the photo's rotation). If the
   * webview drew a blank picture, a format the TV reads goes as it is; the TV fits it itself.
   */
  function shrink(file) {
    return new Promise((resolve, reject) => {
      const img = new Image();
      img.onload = () => {
        const s = Math.min(1, MAX / img.naturalWidth, MAX / img.naturalHeight);
        const c = document.createElement('canvas');
        c.width = Math.round(img.naturalWidth * s);
        c.height = Math.round(img.naturalHeight * s);
        c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
        if (blank(c)) {
          if (TV_READS.includes(file.type)) resolve(file);
          else reject();
          return;
        }
        c.toBlob((b) => (b ? resolve(b) : reject()), 'image/jpeg', 0.92);
      };
      img.onerror = reject;
      img.src = URL.createObjectURL(file);
    });
  }

  async function choose() {
    const file = input.files[0];
    if (!file) return;
    error = '';
    try {
      photo = await shrink(file);
      preview = URL.createObjectURL(photo);
    } catch {
      photo = null;
      preview = '';
      error = t('bg_bad_file');
    }
  }

  function send() {
    return exclusive(async () => {
      error = '';
      sending = true;
      w.flowRunning = true;
      try {
        await setWallpaper(new Uint8Array(await photo.arrayBuffer()));
        go('done');
      } catch (e) {
        error = fromBackend(text(e));
      } finally {
        sending = false;
        w.flowRunning = false;
      }
    });
  }
</script>

<h1 class="text-2xl font-semibold tracking-tight">{t('bg_title')}</h1>
<p class="mt-1 opacity-70">{t('bg_lead')}</p>

<input bind:this={input} type="file" accept="image/*" class="hidden" onchange={choose} />

<button
  class="mt-8 grid aspect-video w-full place-items-center overflow-hidden rounded-box border border-dashed border-base-300 bg-base-200/60 bg-cover bg-center text-sm transition-colors hover:border-primary/50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary disabled:opacity-50"
  style={preview ? `background-image: url(${preview}); border-style: solid` : ''}
  disabled={w.busy}
  onclick={() => input.click()}
>
  {#if !preview}
    <span class="flex flex-col items-center gap-2 opacity-70">
      <Icon name="image" class="size-8" />
      {t('bg_pick')}
    </span>
  {/if}
</button>
{#if preview}
  <button class="btn btn-ghost btn-sm mt-2" disabled={w.busy} onclick={() => input.click()}>{t('bg_change')}</button>
{/if}

{#if sending}
  <div role="status" class="alert alert-soft alert-info mt-6">
    <span class="loading loading-spinner loading-sm"></span>
    <span>{t('bg_sending')}</span>
  </div>
{/if}
{#if error}
  <div role="alert" class="alert alert-soft alert-error mt-6">
    <Icon name="alert" class="size-5" />
    <span>{error}</span>
  </div>
{/if}

<div class="mt-10 flex justify-between">
  <button class="btn btn-ghost" disabled={w.busy} onclick={() => go('connect')}>
    <Icon name="back" class="size-4" /> {t('back')}
  </button>
  <button class="btn btn-primary" disabled={w.busy || !photo} onclick={send}>
    {t('bg_send')} <Icon name="next" class="size-4" />
  </button>
</div>
