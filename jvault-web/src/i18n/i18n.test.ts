import { describe, expect, it } from 'vitest';
import { pickLanguage } from './index';
import { STRINGS } from './strings';

describe('pickLanguage', () => {
  it('prefers what the person chose over everything else', () => {
    expect(pickLanguage('fr', 'de_DE', ['nl-NL'])).toBe('fr');
  });

  it('falls back to the Jira account locale, whatever its shape', () => {
    expect(pickLanguage(null, 'en_GB', ['de'])).toBe('en');
    expect(pickLanguage(null, 'pt-BR', [])).toBe('pt');
    expect(pickLanguage(null, 'lb_LU', [])).toBe('lb');
  });

  it('falls back to the browser when Jira says nothing', () => {
    expect(pickLanguage(null, null, ['it-IT', 'en'])).toBe('it');
  });

  it('skips languages it has no translation for rather than showing keys', () => {
    // A Jira account set to Polish should not leave the whole page blank.
    expect(pickLanguage(null, 'pl_PL', ['sv-SE', 'es-ES'])).toBe('es');
  });

  it('ends at English', () => {
    expect(pickLanguage(null, null, [])).toBe('en');
    expect(pickLanguage(null, 'zz', ['zz'])).toBe('en');
  });
});

describe('translations', () => {
  it('every language defines every string English does', () => {
    // A missing key is `undefined` rendered into the page, which looks like a bug in the data
    // rather than a gap in the translation.
    const expected = Object.keys(STRINGS.en).sort();

    for (const [language, strings] of Object.entries(STRINGS)) {
      expect(Object.keys(strings).sort(), `${language} is missing strings`).toEqual(expected);
    }
  });

  it('no language has silently kept the English text for a visible label', () => {
    // Not a rule for every string — "Ticket" is "Ticket" in most of these — but the page title
    // being identical across eight languages means somebody pasted and forgot.
    const titles = Object.values(STRINGS).map((strings) => strings.createIssue);

    expect(new Set(titles).size).toBe(titles.length);
  });
});
