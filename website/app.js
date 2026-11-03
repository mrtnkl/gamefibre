// Complete, white-filled cards based on the app mark; they fan out using the
// original website's easing, stagger, and six-second hero duration.
class GameFibreIcon extends HTMLElement {
  connectedCallback() {
    if (this.shadowRoot) return;
    const root = this.attachShadow({ mode: "open" });
    root.innerHTML = `
      <style>
        :host{display:block}svg{display:block;width:100%;height:100%;overflow:visible}
        path{fill:#fff;stroke:#0d6efd;stroke-width:52;stroke-linecap:round;stroke-linejoin:round}
        .middle{transform-origin:620px 640px;animation:middle var(--duration) cubic-bezier(.2,.72,.2,1) 40ms both}
        .front{transform-origin:730px 527px;animation:front var(--duration) cubic-bezier(.2,.72,.2,1) 110ms both}
        @keyframes middle{from{transform:translate(-100px,110px) rotate(-10deg)}to{transform:none}}
        @keyframes front{from{transform:translate(-200px,220px) rotate(-20deg)}to{transform:none}}
        @media(prefers-reduced-motion:reduce){path{animation:none!important}}
      </style>
      <svg viewBox="22 22 64 64" aria-hidden="true">
        <g transform="translate(20.475 18.9375) scale(.055)">
          <path d="M312,465 L269,483 C214,505 191,555 214,611 L357,989 C375,1036 425,1069 478,1052 L752,956 C780,946 800,929 812,910 C850,894 880,868 860,816 L720,438 C700,382 640,349 586,369 L312,465 Z"/>
          <path class="middle" d="M446,334 L418,339 C350,351 305,397 320,467 L393,861 C403,922 447,969 511,956 L837,901 C874,895 910,871 921,833 C930,813 937,799 933,785 L860,391 C847,321 795,278 727,290 L446,334 Z"/>
          <path class="front" d="M567,218 L893,218 C959,218 1012,271 1012,337 L1012,717 C1012,783 959,836 893,836 L567,836 C501,836 448,783 448,717 L448,337 C448,271 501,218 567,218 Z"/>
        </g>
      </svg>`;
    const duration = Number(this.getAttribute("animate") ?? 6000);
    this.style.setProperty(
      "--duration",
      `${Number.isFinite(duration) ? Math.max(0, duration) : 6000}ms`,
    );
  }
}
customElements.define("gamefibre-icon", GameFibreIcon);

// Each preview gets its own three-second viewing delay and seamless slide loop.
document.querySelectorAll(".screenshot-carousel").forEach((carousel) => {
  const viewport = carousel.querySelector(".screenshot-viewport");
  const track = carousel.querySelector(".screenshot-track");
  const slides = [...track.children];
  if (slides.length < 2) return;
  const controls = carousel.querySelector(".carousel-controls");
  const pauseButton = carousel.querySelector("[data-carousel-pause]");
  const motion = window.matchMedia("(prefers-reduced-motion: reduce)");
  let paused = motion.matches;
  let inView = false;
  let index = 0;
  let moving = false;
  let timer;

  const clone = (slide) => {
    const copy = slide.cloneNode(true);
    copy.alt = "";
    copy.setAttribute("aria-hidden", "true");
    copy.loading = "eager";
    return copy;
  };
  track.prepend(clone(slides.at(-1)));
  track.append(clone(slides[0]));
  const position = (slot) => `translateX(${-100 * slot}%)`;
  track.style.transform = position(1);
  controls.hidden = false;

  function update() {
    slides.forEach((slide, number) => {
      slide.setAttribute("aria-hidden", String(number !== index));
    });
    pauseButton.textContent = paused ? "▶" : "Ⅱ";
    pauseButton.setAttribute("aria-label", `${paused ? "Play" : "Pause"} ${carousel.classList.contains("portrait-preview") ? "portrait" : "landscape"} slideshow`);
  }

  function schedule() {
    clearTimeout(timer);
    if (!inView || document.hidden || paused || moving || !slides[index].complete || !slides[index].naturalWidth) return;
    timer = setTimeout(() => advance(1), 3000);
  }

  async function advance(direction) {
    clearTimeout(timer);
    if (moving) return;
    moving = true;
    const next = (index + direction + slides.length) % slides.length;
    try {
      // Keep the current screenshot until its replacement has fully decoded.
      slides[next].loading = "eager";
      await slides[next].decode();
      const animation = track.animate(
        [
          { transform: position(index + 1) },
          { transform: position(index + 1 + direction) },
        ],
        { duration: motion.matches ? 0 : 650, easing: "cubic-bezier(.4, 0, .2, 1)", fill: "forwards" },
      );
      await animation.finished;
      index = next;
      track.style.transform = position(index + 1);
      animation.cancel();
      update();
    } catch {
      // Leave the usable screenshot in place if another image cannot load.
    } finally {
      moving = false;
      schedule();
    }
  }

  carousel.querySelector("[data-carousel-next]").addEventListener("click", () => advance(1));
  carousel.querySelector("[data-carousel-previous]").addEventListener("click", () => advance(-1));
  pauseButton.addEventListener("click", () => {
    paused = !paused;
    update();
    schedule();
  });
  slides.forEach((slide) => slide.addEventListener("load", schedule));
  motion.addEventListener("change", () => {
    paused = motion.matches;
    update();
    schedule();
  });
  document.addEventListener("visibilitychange", schedule);
  const observer = new IntersectionObserver((entries) => {
    inView = entries[0].isIntersecting && entries[0].intersectionRatio >= .5;
    schedule();
  }, { threshold: [0, .5] });
  observer.observe(viewport);
  update();
});

const releaseUrl = "https://github.com/mrtnkl/gamefibre/releases/latest";
const message = document.querySelector(".download-message");
const messageText = message.querySelector("p");
const releaseLink = message.querySelector("a");
let downloading = false;

message.querySelector("button").addEventListener("click", () => {
  message.hidden = true;
});

// Resolve on each click: versioned APK names can change with every release.
// Ordinary hrefs still lead to the latest release with JavaScript disabled.
document.querySelectorAll("[data-download]").forEach((link) => {
  link.addEventListener("click", async (event) => {
    if (
      event.button !== 0 ||
      event.metaKey ||
      event.ctrlKey ||
      event.shiftKey ||
      event.altKey
    )
      return;
    event.preventDefault();
    if (downloading) return;
    downloading = true;
    messageText.textContent = "Finding the latest APK…";
    releaseLink.hidden = true;
    message.hidden = false;
    document
      .querySelectorAll("[data-download]")
      .forEach((button) => button.setAttribute("aria-busy", "true"));
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 12000);
    try {
      const response = await fetch(
        "https://api.github.com/repos/mrtnkl/gamefibre/releases/latest",
        {
          headers: { Accept: "application/vnd.github+json" },
          cache: "no-store",
          signal: controller.signal,
        },
      );
      if (!response.ok) throw new Error("Release unavailable");
      const release = await response.json();
      const apk = release.assets?.find(
        (asset) =>
          /^gamefibre(?:-.*)?\.apk$/i.test(asset.name) &&
          asset.state === "uploaded",
      );
      if (!apk || release.draft || release.prerelease)
        throw new Error("No stable APK");
      const url = new URL(apk.browser_download_url);
      if (
        url.origin !== "https://github.com" ||
        !url.pathname.startsWith("/mrtnkl/gamefibre/releases/download/")
      )
        throw new Error("Invalid download URL");
      message.hidden = true;
      window.location.assign(url.href);
    } catch {
      messageText.textContent =
        "Couldn’t start the download. You can get the APK from the latest GitHub release.";
      releaseLink.href = releaseUrl;
      releaseLink.hidden = false;
    } finally {
      clearTimeout(timeout);
      downloading = false;
      document
        .querySelectorAll("[data-download]")
        .forEach((button) => button.removeAttribute("aria-busy"));
    }
  });
});
