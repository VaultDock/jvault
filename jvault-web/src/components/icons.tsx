/** Inline so the page makes no network request for decoration, and inherits currentColor. */
const stroke = {
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.8,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
};

export const VaultIcon = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <rect x="3" y="11" width="18" height="10" rx="2" {...stroke} />
    <path d="M7 11V7a5 5 0 0 1 10 0v4" {...stroke} />
  </svg>
);

export const SplitIcon = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <path d="M12 3v18M5 8l-2 4 2 4M19 8l2 4-2 4" {...stroke} />
  </svg>
);

export const InfoIcon = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <circle cx="12" cy="12" r="9" {...stroke} />
    <path d="M12 11v5M12 8h.01" {...stroke} />
  </svg>
);

export const AlertIcon = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <path d="M12 4l9 16H3l9-16zM12 10v4M12 17h.01" {...stroke} />
  </svg>
);

export const CheckIcon = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <path d="M4 12.5l5 5L20 6.5" {...stroke} strokeWidth={2.2} />
  </svg>
);

export const LockIcon = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <rect x="5" y="10" width="14" height="10" rx="2" {...stroke} />
    <path d="M8 10V7a4 4 0 0 1 8 0v3" {...stroke} />
  </svg>
);

export const EmptyIcon = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <path d="M4 7h16M4 12h16M4 17h10" {...stroke} />
  </svg>
);

export const CameraIcon = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <path d="M4 8h3l1.5-2h7L17 8h3a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1z"
          {...stroke} />
    <circle cx="12" cy="13" r="3.2" {...stroke} />
  </svg>
);

export const BoldIcon = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <path d="M7 5h6a3.5 3.5 0 0 1 0 7H7zM7 12h7a3.5 3.5 0 0 1 0 7H7z" {...stroke} />
  </svg>
);

export const ItalicIcon = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <path d="M15 5h-5M14 19H9M14 5l-4 14" {...stroke} />
  </svg>
);

export const BulletIcon = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <path d="M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01" {...stroke} />
  </svg>
);

export const NumberedIcon = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <path d="M10 6h10M10 12h10M10 18h10M4 5h1v4M4 9h2M4 15h2v2H4v2h2" {...stroke} strokeWidth={1.5} />
  </svg>
);

export const CodeIcon = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <path d="M9 8l-4 4 4 4M15 8l4 4-4 4" {...stroke} />
  </svg>
);

export const QuoteIcon = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <path d="M9 7H5v5h4c0 3-1 4-3 5M19 7h-4v5h4c0 3-1 4-3 5" {...stroke} />
  </svg>
);
