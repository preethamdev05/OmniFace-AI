import { NextRequest, NextResponse } from 'next/server';
import { authenticateSession } from '@/lib/api-auth';
import { resolveOrgEntitlements } from '@/lib/entitlements';

export async function GET(req: NextRequest) {
  const session = await authenticateSession(req);
  if (!session) {
    return NextResponse.json({ authenticated: false, error: 'Unauthorized' }, { status: 401 });
  }

  const entitlements = await resolveOrgEntitlements(session.orgId);

  return NextResponse.json({
    authenticated: true,
    user: {
      id: session.userId,
      email: session.email,
      fullName: session.fullName,
      role: session.role,
      orgId: session.orgId,
      orgName: session.orgName,
      tier: entitlements.tier,
    },
    entitlements,
  });
}
