import { createContext, useContext, useMemo, useState, type ReactNode } from 'react';
import { LANGUAGES, STRINGS, type Language, type Strings } from './strings';

export { LANGUAGES, type Language };

/**
 * Which language to show, in order of how much it means.
 *
 * A choice the person made beats their Jira account's setting, which beats their browser's,
 * which beats English. The Jira locale is not the strongest signal even though it is the most
 * specific: with a service account it is one setting shared by everybody, so treating it as the
 * final word would hand the whole deployment whatever language that account happens to use.
 */
export function pickLanguage(
  chosen: string | null,
  jiraLocale: string | null,
  browser: readonly string[],
): Language {
  for (const candidate of [chosen, jiraLocale, ...browser]) {
    const language = normalise(candidate);
    if (language) {
      return language;
    }
  }
  return 'en';
}

/** `en_GB`, `pt-BR`, `de` all reduce to the part that selects a translation. */
function normalise(locale: string | null | undefined): Language | null {
  if (!locale) {
    return null;
  }
  const base = locale.toLowerCase().replace('_', '-').split('-')[0];
  return base && base in LANGUAGES ? (base as Language) : null;
}

interface Translation {
  t: Strings;
  language: Language;
  setLanguage: (language: Language) => void;
}

const TranslationContext = createContext<Translation>({
  t: STRINGS.en,
  language: 'en',
  setLanguage: () => {},
});

const STORAGE_KEY = 'jvault.language';

export function TranslationProvider({
  jiraLocale,
  children,
}: {
  jiraLocale: string | null;
  children: ReactNode;
}) {
  const [chosen, setChosen] = useState<Language | null>(() => readStored());

  const value = useMemo<Translation>(() => {
    const language = pickLanguage(chosen, jiraLocale, navigator.languages ?? [navigator.language]);
    return {
      language,
      t: STRINGS[language],
      setLanguage: (next) => {
        setChosen(next);
        store(next);
      },
    };
  }, [chosen, jiraLocale]);

  return <TranslationContext.Provider value={value}>{children}</TranslationContext.Provider>;
}

export function useT(): Translation {
  return useContext(TranslationContext);
}

/**
 * Where this deployment's Jira lives.
 *
 * <p>A context rather than a prop threaded through four components: every page that shows an
 * issue key wants to link to it, and none of them wants to know how the URL is built.
 */
const JiraSiteContext = createContext<string | null>(null);

export function JiraSiteProvider({
  baseUrl,
  children,
}: {
  baseUrl: string | null;
  children: ReactNode;
}) {
  return <JiraSiteContext.Provider value={baseUrl}>{children}</JiraSiteContext.Provider>;
}

/** The issue's page in Jira, or null when this deployment has not said where Jira is. */
export function useJiraIssueUrl(issueKey: string | null | undefined): string | null {
  const baseUrl = useContext(JiraSiteContext);
  return baseUrl && issueKey ? `${baseUrl}/browse/${encodeURIComponent(issueKey)}` : null;
}

/** A remembered preference is a convenience, so a browser that refuses storage is not an error. */
function readStored(): Language | null {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    return stored && stored in LANGUAGES ? (stored as Language) : null;
  } catch {
    return null;
  }
}

function store(language: Language) {
  try {
    localStorage.setItem(STORAGE_KEY, language);
  } catch {
    // A private window, or site data turned off. The page still works for this session.
  }
}
