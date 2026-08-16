########################################################################
# SSM-only bastion: no SSH, no public IP, no inbound rule at all. The
# SSM agent (pre-installed on Amazon Linux 2023) makes an OUTBOUND
# connection to AWS Systems Manager; `aws ssm start-session` with
# port-forwarding tunnels through that existing connection. Access is
# controlled entirely by IAM (ssm:StartSession on this instance), not
# network rules or SSH keys - nothing on the internet can ever open a
# connection TO this instance.
#
# Launches running - stop it via the console/CLI between uses
# (`aws ec2 stop-instances`) since it's billed while running regardless
# of load; a t4g.micro is cheap enough (~$6/mo) that leaving it running
# is also a reasonable choice if the stop/start friction isn't worth it.
########################################################################

data "aws_ssm_parameter" "al2023_arm64" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
}

resource "aws_iam_role" "bastion" {
  name = "cadence-${var.environment}-bastion"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  tags = var.tags
}

# AWS-managed policy giving the SSM agent exactly what it needs to register
# and accept sessions - nothing broader (no S3/other AWS API access implied).
resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.bastion.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "bastion" {
  name = "cadence-${var.environment}-bastion"
  role = aws_iam_role.bastion.name
}

resource "aws_security_group" "bastion" {
  name_prefix = "cadence-${var.environment}-bastion-"
  description = "SSM-only bastion - no ingress rules at all, SSM does not need any"
  vpc_id      = var.vpc_id

  egress {
    description = "SSM agent (outbound) + psql to RDS"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.tags, { Name = "cadence-${var.environment}-bastion-sg" })

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_instance" "bastion" {
  ami                    = data.aws_ssm_parameter.al2023_arm64.value
  instance_type          = var.instance_type
  subnet_id              = var.subnet_id
  iam_instance_profile   = aws_iam_instance_profile.bastion.name
  vpc_security_group_ids = [aws_security_group.bastion.id]
  # No key_pair - SSM Session Manager is the only access path, so there's no SSH
  # keypair to generate, distribute, or rotate.

  # postgresql16 may not be the exact package name AL2023's repos ship under by
  # the time this runs - if `psql` isn't on PATH after first connecting, check
  # `dnf list postgresql*` on the instance and adjust here.
  user_data = <<-EOF
    #!/bin/bash
    dnf install -y postgresql16 || dnf install -y postgresql15 || true
  EOF

  tags = merge(var.tags, { Name = "cadence-${var.environment}-bastion" })
}
