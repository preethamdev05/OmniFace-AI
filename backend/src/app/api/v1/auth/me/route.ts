import { NextRequest, NextResponse } from 'next/server';
import { authenticateSession } from '@/lib/api-auth';

export async function GET(req: NextRequest) {
  const session = await authenticateSession(req);
  if (!session) {
    return NextResponse.json({ authenticated: false, error: 'Unauthorized' }, { status: 401 });
  }

  return NextResponse.json({
    authenticated: true,
    user: {
      id: session.userId,
      email: session.email,
      fullName: session.fullName,
      role: session.role,
      orgId: session.orgId,
      orgName: session.orgName,
      tier: session.tier,
    },
  });
}
