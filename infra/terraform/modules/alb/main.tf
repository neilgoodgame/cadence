########################################################################
# Public-facing ALB in front of the ECS service. HTTPS via the ACM cert
# passed in (see modules/acm and envs/staging/main.tf) - HTTP just
# redirects to HTTPS, it doesn't forward directly anymore.
########################################################################

resource "aws_security_group" "alb" {
  name_prefix = "cadence-${var.environment}-alb-"
  description = "Public ALB - open to the internet on 80 (443 once ACM/HTTPS is added)" # NOT changed even though stale now - editing aws_security_group's top-level description is ForceNew (ingress/egress rule descriptions aren't) and would force-replace this already-live SG for zero real benefit
  vpc_id      = var.vpc_id

  ingress {
    description = "HTTP from anywhere - redirects to HTTPS, see aws_lb_listener.http"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTPS from anywhere"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "Allow all outbound (standard SG hygiene - real traffic is just to the ECS tasks SG, restricted from the ECS side instead)"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.tags, { Name = "cadence-${var.environment}-alb-sg" })

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_lb" "this" {
  name               = "cadence-${var.environment}"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = var.public_subnet_ids

  tags = merge(var.tags, { Name = "cadence-${var.environment}-alb" })
}

# target_type = "ip" is required for Fargate awsvpc networking - each task gets
# its own ENI/IP, there's no EC2 instance to register by instance ID.
resource "aws_lb_target_group" "backend" {
  name        = "cadence-${var.environment}-backend"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = var.vpc_id
  target_type = "ip"

  health_check {
    path                = "/healthz"
    matcher             = "200"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  tags = merge(var.tags, { Name = "cadence-${var.environment}-backend-tg" })
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"

    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.backend.arn
  }
}
