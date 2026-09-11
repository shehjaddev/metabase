import type { ComponentType, ReactNode } from "react";

import type { LinkProps } from "metabase/common/components/Link";
import { PluginPlaceholder } from "metabase/plugins/components/PluginPlaceholder";
import type Question from "metabase-lib/v1/Question";
import type {
  Card,
  Dashboard,
  Database as DatabaseType,
  IconName,
} from "metabase-types/api";

import { definePluginSlot } from "../slot";

export type InsightsLinkProps = (
  | {
      question: Pick<Question, "id" | "collection">;
      dashboard?: never;
    }
  | {
      question?: never;
      dashboard: Pick<Dashboard, "id" | "collection">;
    }
) &
  Omit<LinkProps, "to">;

export interface InsightsMenuItemProps {
  card: Pick<Card, "id" | "collection">;
  label?: string;
  iconName?: IconName;
  withDivider?: boolean;
}

const getDefaultPluginAudit = () => ({
  isEnabled: false,
  isAuditDb: (_db: DatabaseType) => false,
  // Unjustified type cast. FIXME
  InsightsLink: PluginPlaceholder as ComponentType<InsightsLinkProps>,
  // Unjustified type cast. FIXME
  InsightsMenuItem: PluginPlaceholder as ComponentType<InsightsMenuItemProps>,
  isAiAuditingEnabled: false,
  getAiAuditingRoutes: (): ReactNode => null,
});

export const PLUGIN_AUDIT = definePluginSlot(getDefaultPluginAudit);
